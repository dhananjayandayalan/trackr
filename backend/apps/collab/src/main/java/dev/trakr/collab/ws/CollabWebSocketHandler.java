package dev.trakr.collab.ws;

import dev.trakr.collab.protocol.AwarenessCodec;
import dev.trakr.collab.protocol.AwarenessCodec.Entry;
import dev.trakr.collab.protocol.MessageReader;
import dev.trakr.collab.protocol.MessageWriter;
import dev.trakr.collab.protocol.Messages;
import dev.trakr.collab.protocol.Messages.Awareness;
import dev.trakr.collab.protocol.Messages.Message;
import dev.trakr.collab.protocol.Messages.QueryAwareness;
import dev.trakr.collab.protocol.Messages.SyncStep1;
import dev.trakr.collab.protocol.Messages.SyncStep2;
import dev.trakr.collab.protocol.Messages.TrakrCheckpoint;
import dev.trakr.collab.protocol.Messages.TrakrSeed;
import dev.trakr.collab.protocol.Messages.TrakrSnapshot;
import dev.trakr.collab.protocol.Messages.Update;
import dev.trakr.collab.protocol.ProtocolException;
import dev.trakr.collab.relay.CollabRelay;
import dev.trakr.collab.room.CheckpointScheduler;
import dev.trakr.collab.room.Peer;
import dev.trakr.collab.room.Room;
import dev.trakr.collab.room.RoomPresence;
import dev.trakr.collab.room.RoomRegistry;
import dev.trakr.collab.security.Connection;
import dev.trakr.collab.security.ConnectionLimits;
import dev.trakr.collab.store.CollabStore;
import dev.trakr.collab.store.CollabStore.Snapshot;
import dev.trakr.collab.store.CollabStore.StoredUpdate;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * The relay itself (06-collab §3): one instance handles every socket. Spring calls it when a socket opens, for
 * every binary frame, and when the socket closes. Frames of one socket arrive one at a time, in order; frames of
 * different sockets arrive in parallel, each on its own (virtual) thread — so everything shared lives in the Room,
 * behind its lock.
 */
@Component
class CollabWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CollabWebSocketHandler.class);
    private static final String PEER = Peer.class.getName();
    private static final String ROOM = Room.class.getName();

    private final RoomRegistry rooms;
    private final CollabStore store;
    private final CollabRelay relay;
    private final CheckpointScheduler checkpoints;
    private final RoomPresence presence;
    private final ConnectionLimits limits;
    private final Clock clock;

    CollabWebSocketHandler(RoomRegistry rooms, CollabStore store, CollabRelay relay, CheckpointScheduler checkpoints,
            RoomPresence presence, ConnectionLimits limits, Clock clock) {
        this.rooms = rooms;
        this.store = store;
        this.relay = relay;
        this.checkpoints = checkpoints;
        this.presence = presence;
        this.limits = limits;
        this.clock = clock;
    }

    // BLOCK 1: afterConnectionEstablished — the "on open" steps of 06-collab §3
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Connection connection = Connection.from(session.getAttributes());
        if (connection == null) {
            close(session, CloseStatus.SERVER_ERROR);
            return;
        }
        if (!limits.tryOpen(connection.userId())) {
            close(session, CloseStatus.POLICY_VIOLATION.withReason("too many connections"));
            return;
        }
        Peer peer = new Peer(session, connection);
        session.getAttributes().put(PEER, peer);
        // in the room's socket set first, then the stored state: an update stored in between reaches this socket
        // twice (broadcast + database), never zero times — and Yjs ignores what it already has
        Room room = rooms.join(connection.issueId(), peer);
        session.getAttributes().put(ROOM, room);
        if (peer.canEdit()) {
            presence.announce(room.issueId());
        }
        room.locked(() -> {
            // 1. "what do you have?" — an empty state vector, so the client answers with everything, offline edits included
            peer.send(MessageWriter.write(new SyncStep1(Messages.EMPTY_STATE_VECTOR)));
            // 2 + 3. the snapshot and every update after it
            boolean nothingStored = sendStoredState(room, peer);
            // the issue has never been edited here: one member fills the document from the issue's old description
            if (room.takeSeed(peer, nothingStored)) {
                peer.send(MessageWriter.write(new TrakrSeed()));
            }
            // 4. who else is here
            List<Entry> others = room.awarenessEntries();
            if (!others.isEmpty()) {
                peer.send(MessageWriter.awareness(AwarenessCodec.encode(others)));
            }
        });
        // updates no snapshot covers yet (everyone left before a checkpoint, or collab restarted): this member can
        // provide one, so the description reaches the api, search and compaction without waiting for the next edit
        if (peer.canEdit() && room.locked(room::needsCheckpoint)) {
            checkpoints.touch(room);
        }
        log.debug("joined room={} peer={} role={} peers={} thread={}", room.issueId(), peer, connection.grant().role(),
                room.size(), Thread.currentThread());
    }

    // steps 2–3, also the answer to a client's syncStep1. True when nothing at all is stored for the issue.
    private boolean sendStoredState(Room room, Peer peer) {
        Optional<Snapshot> snapshot = store.loadSnapshot(room.issueId());
        long upto = snapshot.map(Snapshot::uptoUpdateId).orElse(0L);
        List<StoredUpdate> updates = store.loadUpdatesAfter(room.issueId(), upto);
        peer.send(MessageWriter.write(new SyncStep2(snapshot.map(Snapshot::state).orElse(Messages.EMPTY_UPDATE))));
        for (StoredUpdate update : updates) {
            peer.send(MessageWriter.update(update.update()));
        }
        room.noteStored(upto, updates.isEmpty() ? 0 : updates.getLast().id());
        return snapshot.isEmpty() && updates.isEmpty();
    }

    // BLOCK 2: handleBinaryMessage — parse, then one case per message kind (the compiler checks they're all here)
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Peer peer = (Peer) session.getAttributes().get(PEER);
        Room room = (Room) session.getAttributes().get(ROOM);
        if (peer == null || room == null) {
            return;
        }
        Message parsed;
        try {
            parsed = MessageReader.read(bytes(message.getPayload()));
        } catch (ProtocolException e) {
            log.info("closing {}: {}", peer, e.getMessage());
            peer.close(CloseStatus.NOT_ACCEPTABLE.withReason("bad frame"));
            return;
        }
        switch (parsed) {
            case SyncStep1 m -> room.locked(() -> {
                sendStoredState(room, peer);
            });
            // the client's answer to our syncStep1: its whole document. Empty ("0x00 0x00") for a new client.
            case SyncStep2 m -> {
                if (!Arrays.equals(m.update(), Messages.EMPTY_UPDATE)) {
                    onUpdate(room, peer, m.update());
                }
            }
            case Update m -> onUpdate(room, peer, m.update());
            case Awareness m -> onAwareness(room, peer, m.update());
            case QueryAwareness m -> room.locked(() -> {
                peer.send(MessageWriter.awareness(AwarenessCodec.encode(room.awarenessEntries())));
            });
            case TrakrSnapshot m -> onSnapshot(room, peer, m);
            // server → client only; a client sending these doesn't speak our protocol
            case TrakrCheckpoint m -> peer.close(CloseStatus.NOT_ACCEPTABLE.withReason("unexpected message"));
            case TrakrSeed m -> peer.close(CloseStatus.NOT_ACCEPTABLE.withReason("unexpected message"));
        }
    }

    // BLOCK 3: updates — store, fan out, record; then tell the other instances and the checkpoint timer
    private void onUpdate(Room room, Peer peer, byte[] update) {
        if (!peer.canEdit()) {
            // a viewer's editor is read-only, so this is a modified client: drop it, keep the socket
            log.debug("dropped update from viewer {}", peer);
            return;
        }
        if (!peer.allowUpdate(clock.millis())) {
            // dropping one would leave this client and the others out of step; closing makes it reconnect and resync
            peer.close(CloseStatus.POLICY_VIOLATION.withReason("too many updates"));
            return;
        }
        Long clientId = peer.firstClientIdOrNull();
        long updateId = room.locked(() -> {
            long id = store.appendUpdate(room.issueId(), update, clientId);
            room.broadcast(MessageWriter.update(update), peer);
            room.noteUpdate(id);
            return id;
        });
        log.debug("update room={} id={} bytes={} thread={}", room.issueId(), updateId, update.length,
                Thread.currentThread());
        relay.publishUpdate(peer.connection(), update, updateId, clientId);
        checkpoints.touch(room);
    }

    // BLOCK 4: awareness — remember whose client ids these are, relay the frame unchanged if anything is new
    private void onAwareness(Room room, Peer peer, byte[] update) {
        List<Entry> entries;
        try {
            entries = AwarenessCodec.decode(update);
        } catch (ProtocolException e) {
            peer.close(CloseStatus.NOT_ACCEPTABLE.withReason("bad awareness"));
            return;
        }
        boolean changed = room.locked(() -> {
            boolean newer = room.applyAwareness(entries, peer);
            if (newer) {
                room.broadcast(MessageWriter.awareness(update), peer);
            }
            return newer;
        });
        if (changed) {
            relay.publishAwareness(peer.connection(), update);
        }
    }

    // BLOCK 5: the snapshot reply (06-collab §4 step 4) — only the one we asked for, from the socket we asked
    private void onSnapshot(Room room, Peer peer, TrakrSnapshot snapshot) {
        boolean stored = room.locked(() -> {
            if (!peer.canEdit() || !room.isExpectedSnapshot(peer, snapshot.uptoUpdateId())) {
                log.debug("ignored unexpected snapshot from {} upto={}", peer, snapshot.uptoUpdateId());
                return false;
            }
            boolean saved = store.saveSnapshotAndCompact(room.issueId(), snapshot.state(), snapshot.uptoUpdateId());
            room.snapshotStored(snapshot.uptoUpdateId());
            return saved;
        });
        if (stored) {
            log.debug("snapshot room={} upto={} stateBytes={}", room.issueId(), snapshot.uptoUpdateId(),
                    snapshot.state().length);
            relay.publishSnapshot(peer.connection(), snapshot.text(), snapshot.html(), snapshot.uptoUpdateId());
        }
    }

    // BLOCK 6: afterConnectionClosed — tell the others this socket's clients left, then leave the room
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Peer peer = (Peer) session.getAttributes().get(PEER);
        Room room = (Room) session.getAttributes().get(ROOM);
        if (peer == null || room == null) {
            return;
        }
        limits.closed(peer.connection().userId());
        List<Entry> gone = room.locked(() -> {
            List<Entry> left = room.forget(peer);
            if (!left.isEmpty()) {
                room.broadcast(MessageWriter.awareness(AwarenessCodec.encode(left)), peer);
            }
            return left;
        });
        if (!gone.isEmpty()) {
            relay.publishAwareness(peer.connection(), AwarenessCodec.encode(gone));
        }
        rooms.leave(room, peer);
        if (!room.hasMember()) {
            presence.withdraw(room.issueId());
        }
        if (rooms.find(room.issueId()).isEmpty()) {
            checkpoints.forget(room.issueId());
        }
        log.debug("left room={} peer={} status={}", room.issueId(), peer, status);
    }

    private static byte[] bytes(ByteBuffer payload) {
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        return bytes;
    }

    private static void close(WebSocketSession session, @Nullable CloseStatus status) {
        try {
            session.close(status == null ? CloseStatus.SERVER_ERROR : status);
        } catch (java.io.IOException e) {
            log.debug("close failed: {}", e.getMessage());
        }
    }
}

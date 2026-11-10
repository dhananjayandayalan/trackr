package dev.trakr.collab.room;

import dev.trakr.collab.protocol.AwarenessCodec.Entry;
import dev.trakr.collab.protocol.MessageWriter;
import dev.trakr.collab.protocol.Messages.TrakrCheckpoint;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Everyone on this instance editing one issue's description, and what the server remembers about it in memory
 * (06-collab §6): the sockets, the awareness states, and how far the stored updates and the last snapshot reach.
 *
 * <p>BLOCK 1: one lock per room. The checkpoint handshake (§4) works only if order is strict: when the server
 * sends {@code trakrCheckpoint(upto = 42)} to a client, every update up to 42 must already be in that client's
 * queue in front of it. So "store an update, broadcast it, record its id" happens as one step under the lock, and
 * so does "pick a client and send it the checkpoint". Without the lock, update 41 could still be on its way to the
 * client when the checkpoint for 42 overtakes it — and the snapshot that comes back would miss 41, which the
 * compaction then deletes. A {@code ReentrantLock}, not {@code synchronized}: the same either way since Java 24
 * (virtual threads no longer pin on monitors), but a lock can be asked "do I hold you?", which the checks below use.
 *
 * <p>The socket set is a concurrent set on its own, outside the lock: joining and leaving only touch it, and the
 * registry can ask "empty?" without waiting for a room that is busy storing an update.
 */
public final class Room {

    /** A checkpoint nobody answered within this time may be asked of someone else. */
    static final Duration CHECKPOINT_TIMEOUT = Duration.ofSeconds(10);

    private record Presence(long clock, String json, @Nullable Peer owner) {
    }

    private record Pending(Peer peer, long uptoUpdateId, Instant sentAt) {
    }

    private final UUID issueId;
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();

    // BLOCK 2: guarded by lock
    private final Map<Long, Presence> awareness = new LinkedHashMap<>();
    private long lastUpdateId;
    private long checkpointedUpto;
    private int updatesSinceCheckpoint;
    private @Nullable Pending pending;
    private @Nullable Peer seeder;

    Room(UUID issueId) {
        this.issueId = issueId;
    }

    public UUID issueId() {
        return issueId;
    }

    public <T> T locked(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    public void locked(Runnable work) {
        lock.lock();
        try {
            work.run();
        } finally {
            lock.unlock();
        }
    }

    private void requireLock() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("room " + issueId + " used without its lock");
        }
    }

    // BLOCK 3: sockets (no lock needed)
    void add(Peer peer) {
        peers.add(peer);
    }

    void remove(Peer peer) {
        peers.remove(peer);
    }

    boolean isEmpty() {
        return peers.isEmpty();
    }

    /** A member (not a viewer) is connected here: only then may this instance take the room's checkpoints. */
    public boolean hasMember() {
        return peers.stream().anyMatch(peer -> peer.canEdit() && peer.isOpen());
    }

    public int size() {
        return peers.size();
    }

    // BLOCK 4: fan-out — everyone but the sender (null: everyone, e.g. a frame relayed from another instance)
    public void broadcast(byte[] frame, @Nullable Peer except) {
        requireLock();
        for (Peer peer : peers) {
            if (peer != except) {
                peer.send(frame);
            }
        }
    }

    // BLOCK 5: how far the stored updates reach
    /** What a join just read from the database: the snapshot's {@code upto} and the last stored update id. */
    public void noteStored(long snapshotUpto, long lastStoredId) {
        requireLock();
        checkpointedUpto = Math.max(checkpointedUpto, snapshotUpto);
        lastUpdateId = Math.max(lastUpdateId, Math.max(snapshotUpto, lastStoredId));
    }

    /** An update was stored (here or on another instance) and every local socket has it in its queue. */
    public void noteUpdate(long updateId) {
        requireLock();
        lastUpdateId = Math.max(lastUpdateId, updateId);
        updatesSinceCheckpoint++;
    }

    public int updatesSinceCheckpoint() {
        requireLock();
        return updatesSinceCheckpoint;
    }

    public long lastUpdateId() {
        requireLock();
        return lastUpdateId;
    }

    // BLOCK 6: awareness. Keep the newest state per client id (higher clock wins), and which socket it came from.
    /**
     * Applies an awareness update; {@code from} is the local socket that sent it, null for one relayed from another
     * instance. True when at least one entry was newer than what we had — only then is it worth relaying.
     *
     * <p>Clients re-send other clients' states they receive (y-websocket broadcasts every awareness change it
     * applies). Those echoes carry no newer clock, change nothing here, and must not make the echoing socket the
     * "owner" of someone else's client id — else closing it would announce that other person gone.
     */
    public boolean applyAwareness(List<Entry> entries, @Nullable Peer from) {
        requireLock();
        boolean changed = false;
        for (Entry entry : entries) {
            Presence current = awareness.get(entry.clientId());
            // y-protocols' rule: a higher clock wins; at the same clock only "left" wins (a client removing someone
            // else's state, e.g. after a timeout, can't raise that client's clock)
            boolean newer = current == null || entry.clock() > current.clock()
                    || (entry.clock() == current.clock() && entry.left());
            if (!newer) {
                continue;
            }
            changed = true;
            Peer owner = current == null || current.owner() == null || !current.owner().isOpen()
                    ? from : current.owner();
            if (entry.left()) {
                awareness.remove(entry.clientId());
                if (owner != null) {
                    owner.clientIds().remove(entry.clientId());
                }
            } else {
                awareness.put(entry.clientId(), new Presence(entry.clock(), entry.json(), owner));
                if (owner != null) {
                    owner.clientIds().add(entry.clientId());
                }
            }
        }
        return changed;
    }

    /** Everyone's current state, for a newcomer and for queryAwareness. */
    public List<Entry> awarenessEntries() {
        requireLock();
        List<Entry> entries = new ArrayList<>(awareness.size());
        awareness.forEach((clientId, presence) -> entries.add(new Entry(clientId, presence.clock(), presence.json())));
        return entries;
    }

    /**
     * A socket closed: forget the client ids it spoke for and return their "left" entries (clock + 1) for the
     * others; also release whatever was waiting on it (a checkpoint, the seed).
     */
    public List<Entry> forget(Peer peer) {
        requireLock();
        List<Entry> gone = new ArrayList<>();
        for (Long clientId : peer.clientIds()) {
            Presence presence = awareness.get(clientId);
            if (presence != null && presence.owner() == peer) {
                awareness.remove(clientId);
                gone.add(new Entry(clientId, presence.clock(), presence.json()).gone());
            }
        }
        peer.clientIds().clear();
        if (pending != null && pending.peer() == peer) {
            pending = null;
        }
        if (seeder == peer) {
            seeder = null;
        }
        return gone;
    }

    // BLOCK 7: the checkpoint handshake (06-collab §4)
    /**
     * Asks one connected member for a snapshot covering every update so far. Nothing to do when nothing changed
     * since the last snapshot, or while an earlier request is still unanswered (and not yet timed out).
     */
    public Optional<Peer> requestCheckpoint(Instant now) {
        requireLock();
        if (lastUpdateId <= checkpointedUpto) {
            return Optional.empty();
        }
        if (pending != null && pending.peer().isOpen() && pending.sentAt().plus(CHECKPOINT_TIMEOUT).isAfter(now)) {
            return Optional.empty();
        }
        Optional<Peer> target = peers.stream().filter(peer -> peer.canEdit() && peer.isOpen()).findFirst();
        target.ifPresent(peer -> {
            peer.send(MessageWriter.write(new TrakrCheckpoint(lastUpdateId)));
            pending = new Pending(peer, lastUpdateId, now);
            updatesSinceCheckpoint = 0;
        });
        return target;
    }

    /** Updates newer than the last snapshot, and a member here who could provide the next one. */
    public boolean needsCheckpoint() {
        requireLock();
        return lastUpdateId > checkpointedUpto && hasMember();
    }

    /** Is this the snapshot we asked this socket for? Anything else (unasked, wrong upto, a viewer) is dropped. */
    public boolean isExpectedSnapshot(Peer from, long uptoUpdateId) {
        requireLock();
        return pending != null && pending.peer() == from && pending.uptoUpdateId() == uptoUpdateId;
    }

    public void snapshotStored(long uptoUpdateId) {
        requireLock();
        checkpointedUpto = Math.max(checkpointedUpto, uptoUpdateId);
        pending = null;
    }

    // BLOCK 8: seeding — an issue whose description predates collaborative editing has no Yjs document yet
    /**
     * True for exactly one member: the first to join a room with nothing stored. That client fills the new document
     * from the issue's HTML; everyone else receives the result as an ordinary update. Released again if that client
     * leaves before anything was stored.
     */
    public boolean takeSeed(Peer peer, boolean nothingStored) {
        requireLock();
        if (!nothingStored || lastUpdateId > 0 || !peer.canEdit() || (seeder != null && seeder.isOpen())) {
            return false;
        }
        seeder = peer;
        return true;
    }
}

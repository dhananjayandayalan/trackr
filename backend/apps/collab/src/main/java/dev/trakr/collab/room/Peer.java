package dev.trakr.collab.room;

import dev.trakr.collab.security.Connection;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

/**
 * One open socket in a room: who it is, what it may do, and a way to send it frames.
 *
 * <p>Sending is the subtle part. A raw WebSocketSession allows one send at a time, but frames for one socket come
 * from many threads: its own handler, other sockets' handlers (a broadcast), the Kafka relay, the checkpoint timer.
 * ConcurrentWebSocketSessionDecorator serialises them: whoever arrives while a send is running only queues its frame,
 * and the running sender drains the queue. If the client stops reading — a send takes over 5 s, or 4 MiB pile up —
 * the decorator gives up and we close the socket; the client reconnects and resyncs, losing nothing.
 */
public final class Peer {

    private static final Logger log = LoggerFactory.getLogger(Peer.class);
    static final int SEND_TIME_LIMIT_MS = 5_000;
    static final int BUFFER_SIZE_LIMIT = 4 * 1024 * 1024;
    /** 06-collab §5: update frames per second per socket. */
    static final int UPDATES_PER_SECOND = 30;

    private final WebSocketSession session;
    private final Connection connection;
    // the Y.Doc client ids this socket speaks for (from its awareness frames), in the order they appeared
    private final Set<Long> clientIds = new CopyOnWriteArraySet<>();
    // rate limit: a fixed one-second window
    private long windowStart;
    private int framesInWindow;

    public Peer(WebSocketSession raw, Connection connection) {
        this.session = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        this.connection = connection;
    }

    public String id() {
        return session.getId();
    }

    public Connection connection() {
        return connection;
    }

    public boolean canEdit() {
        return connection.canEdit();
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    Set<Long> clientIds() {
        return clientIds;
    }

    /** The client id to store with this socket's updates; null until its first awareness frame. */
    public @Nullable Long firstClientIdOrNull() {
        return clientIds.stream().findFirst().orElse(null);
    }

    public void send(byte[] frame) {
        try {
            session.sendMessage(new BinaryMessage(frame));
        } catch (SessionLimitExceededException e) {
            log.info("closing slow socket {}: {}", id(), e.getMessage());
            close(e.getStatus());
        } catch (IOException | IllegalStateException e) {
            // the socket is already gone; afterConnectionClosed cleans up
            log.debug("send to {} failed: {}", id(), e.getMessage());
        }
    }

    public void close(CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("close of {} failed: {}", id(), e.getMessage());
        }
    }

    /** True while this socket stays within its update frames per second. */
    public synchronized boolean allowUpdate(long nowMillis) {
        if (nowMillis - windowStart >= 1_000) {
            windowStart = nowMillis;
            framesInWindow = 0;
        }
        framesInWindow++;
        return framesInWindow <= UPDATES_PER_SECOND;
    }

    @Override
    public String toString() {
        return "Peer[" + id() + ", user " + connection.userId() + "]";
    }
}

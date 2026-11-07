package dev.trakr.api.realtime.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The open SSE connections of this api instance, by project (board streams) and by user (/me/stream).
 *
 * <p>Sending never happens on the caller's thread. Each subscriber has its own chain of sends, each
 * step on a fresh virtual thread: {@code broadcast} only appends a step to every chain and returns at
 * once. A browser on a bad connection blocks its own chain — nobody else's, and not the Kafka consumer
 * thread that called {@code broadcast}. The chain (one step after the other, never two at a time) keeps
 * each browser's events in order. A chain that falls more than {@value #MAX_PENDING} events behind is
 * cut off: the browser reconnects with Last-Event-ID and catches up from the replay buffer instead.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    static final int MAX_PENDING = 256;

    /** One frame on the wire; {@code id} null for frames the client must not resume from (presence). */
    public record Frame(@Nullable String id, String name, String data) {
    }

    // BLOCK 1: subscribers
    private final Map<UUID, Set<Subscriber>> byProject = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Subscriber>> byUser = new ConcurrentHashMap<>();
    // a virtual thread is cheap enough to start one per send; there's no pool to size or exhaust
    private final ExecutorService sends = Executors.newVirtualThreadPerTaskExecutor();
    private final List<BiConsumer<UUID, UUID>> leaveListeners = new ArrayList<>();

    /** Called with (projectId, userId) when a project subscriber goes away; PresenceTracker uses it. */
    public void onLeave(BiConsumer<UUID, UUID> listener) {
        leaveListeners.add(listener);
    }

    // BLOCK 2: subscribe
    /**
     * Registers a board stream. Nothing is sent until {@link Subscriber#open} — the caller replays missed
     * events first; live events arriving meanwhile wait behind them.
     */
    public Subscriber subscribeProject(UUID projectId, UUID userId, SseEmitter emitter) {
        return register(new Subscriber(emitter, userId, projectId), byProject.computeIfAbsent(projectId, id -> ConcurrentHashMap.newKeySet()));
    }

    public Subscriber subscribeUser(UUID userId, SseEmitter emitter) {
        return register(new Subscriber(emitter, userId, null), byUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()));
    }

    private Subscriber register(Subscriber subscriber, Set<Subscriber> set) {
        set.add(subscriber);
        // the servlet container tells us when the browser is gone (or the async request ends)
        subscriber.emitter.onCompletion(subscriber::close);
        subscriber.emitter.onTimeout(subscriber::close);
        subscriber.emitter.onError(error -> subscriber.close());
        return subscriber;
    }

    // BLOCK 3: broadcast(projectId, frame)
    public void broadcast(UUID projectId, Frame frame) {
        byProject.getOrDefault(projectId, Set.of()).forEach(subscriber -> subscriber.enqueue(frame));
    }

    public void sendToUser(UUID userId, Frame frame) {
        byUser.getOrDefault(userId, Set.of()).forEach(subscriber -> subscriber.enqueue(frame));
    }

    // BLOCK 4: heartbeat — a comment line every 15 s, so proxies don't close an idle connection
    @Scheduled(fixedRate = 15_000)
    void heartbeat() {
        byProject.values().forEach(set -> set.forEach(Subscriber::ping));
        byUser.values().forEach(set -> set.forEach(Subscriber::ping));
    }

    /** The projects with at least one open stream here, and who is on each. */
    public Map<UUID, Set<UUID>> viewers() {
        Map<UUID, Set<UUID>> viewers = new java.util.HashMap<>();
        byProject.forEach((projectId, set) -> {
            Set<UUID> users = new java.util.HashSet<>();
            set.forEach(subscriber -> users.add(subscriber.userId));
            if (!users.isEmpty()) {
                viewers.put(projectId, users);
            }
        });
        return viewers;
    }

    boolean isWatching(UUID projectId, UUID userId) {
        return byProject.getOrDefault(projectId, Set.of()).stream().anyMatch(s -> s.userId.equals(userId));
    }

    int subscriberCount(UUID projectId) {
        return byProject.getOrDefault(projectId, Set.of()).size();
    }

    /** One open stream. */
    public final class Subscriber {

        private final SseEmitter emitter;
        private final UUID userId;
        private final @Nullable UUID projectId;
        // closed until open(): live frames queue up behind the replay
        private final CompletableFuture<Void> gate = new CompletableFuture<>();
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        // ids sent by the replay; a live frame with one of them arrived twice (published while we read the buffer)
        private final Set<String> replayed = ConcurrentHashMap.newKeySet();
        private CompletableFuture<Void> tail = gate;

        private Subscriber(SseEmitter emitter, UUID userId, @Nullable UUID projectId) {
            this.emitter = emitter;
            this.userId = userId;
            this.projectId = projectId;
        }

        /** Sends {@code first} (a replay, or a resync notice) right now, then lets the queued live frames go. */
        public void open(List<Frame> first) {
            for (Frame frame : first) {
                if (frame.id() != null) {
                    replayed.add(frame.id());
                }
                if (!send(frame)) {
                    break;
                }
            }
            gate.complete(null);
        }

        void enqueue(Frame frame) {
            if (closed.get()) {
                return;
            }
            if (pending.incrementAndGet() > MAX_PENDING) {
                log.info("sse subscriber too slow, disconnecting userId={} projectId={}", userId, projectId);
                close();
                return;
            }
            synchronized (this) {
                // thenRunAsync: the next step starts when this one is done, on a new virtual thread.
                // A step must never throw, or every later step of the chain would be skipped.
                tail = tail.thenRunAsync(() -> {
                    try {
                        if (frame.id() == null || !replayed.contains(frame.id())) {
                            send(frame);
                        }
                    } finally {
                        pending.decrementAndGet();
                    }
                }, sends);
            }
        }

        private void ping() {
            if (closed.get() || !gate.isDone()) {
                return;
            }
            synchronized (this) {
                tail = tail.thenRunAsync(this::sendPing, sends);
            }
        }

        private boolean send(Frame frame) {
            try {
                // fields go on the wire in the order they're added: id first, as in 03-api §4
                SseEmitter.SseEventBuilder event = SseEmitter.event();
                if (frame.id() != null) {
                    event.id(frame.id());
                }
                emitter.send(event.name(frame.name()).data(frame.data(), MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException e) {
                // the browser is gone; IllegalStateException: the emitter was completed meanwhile
                close();
                return false;
            }
        }

        private void sendPing() {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                close();
            }
        }

        /** Idempotent: the servlet callbacks, a failed send and the slow-client cut-off may all call it. */
        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Set<Subscriber> set = projectId == null ? byUser.get(userId) : byProject.get(projectId);
            if (set != null) {
                set.remove(this);
            }
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                // already completed
            }
            if (projectId != null) {
                leaveListeners.forEach(listener -> listener.accept(projectId, userId));
            }
        }

        public boolean isClosed() {
            return closed.get();
        }
    }
}

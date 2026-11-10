package dev.trakr.collab.room;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * When to ask for a snapshot (06-collab §4 step 1): 2 s after the last edit ("the typing stopped"), or at once
 * after 200 updates, so someone who never pauses still gets checkpointed.
 *
 * <p>"2 s after the last edit" is a debounce: every update cancels the pending timer and starts a new one. Only
 * the timer that survives 2 s of silence fires. Timers run on virtual threads — firing one does Redis and socket
 * I/O, and a thread per timer costs nothing.
 */
@Component
public class CheckpointScheduler {

    private static final Logger log = LoggerFactory.getLogger(CheckpointScheduler.class);
    static final Duration IDLE = Duration.ofSeconds(2);
    static final int MAX_UPDATES = 200;

    private final RoomPresence presence;
    private final Clock clock;
    private final ScheduledExecutorService timer =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("collab-checkpoint-", 0).factory());
    private final ConcurrentMap<UUID, ScheduledFuture<?>> idle = new ConcurrentHashMap<>();

    CheckpointScheduler(RoomPresence presence, Clock clock) {
        this.presence = presence;
        this.clock = clock;
    }

    /** An update reached this room (call after {@code room.noteUpdate}, outside the room's lock). */
    public void touch(Room room) {
        int updates = room.locked(room::updatesSinceCheckpoint);
        schedule(room, updates >= MAX_UPDATES ? Duration.ZERO : IDLE);
    }

    /** The room is gone from this instance: drop its timer. */
    public void forget(UUID issueId) {
        ScheduledFuture<?> pending = idle.remove(issueId);
        if (pending != null) {
            pending.cancel(false);
        }
    }

    // one timer per room: a new one replaces (cancels) the one before
    private void schedule(Room room, Duration delay) {
        idle.compute(room.issueId(), (id, previous) -> {
            if (previous != null) {
                previous.cancel(false);
            }
            return timer.schedule(() -> checkpoint(room), delay.toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    void checkpoint(Room room) {
        // another instance with a member in this room has the lower id: it takes the checkpoints, we don't
        if (!presence.isLeader(room.issueId())) {
            return;
        }
        boolean retry = room.locked(() -> {
            var asked = room.requestCheckpoint(clock.instant());
            asked.ifPresent(peer -> log.debug("checkpoint requested room={} from={} upto={}", room.issueId(), peer,
                    room.lastUpdateId()));
            // not asked although there is something new: an earlier request is still unanswered. Look again
            // later — without this, the last edits before everyone stops typing would wait for the next edit.
            return asked.isEmpty() && room.needsCheckpoint();
        });
        if (retry) {
            schedule(room, IDLE);
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }
}

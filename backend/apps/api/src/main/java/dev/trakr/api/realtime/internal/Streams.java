package dev.trakr.api.realtime.internal;

import dev.trakr.api.realtime.internal.SseHub.Frame;
import dev.trakr.api.realtime.internal.SseHub.Subscriber;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Opening a stream: subscribe first, then replay — so nothing published in between is lost. */
@Component
public class Streams {

    private final SseHub hub;
    private final ReplayBuffer replay;
    private final PresenceTracker presence;

    Streams(SseHub hub, ReplayBuffer replay, PresenceTracker presence) {
        this.hub = hub;
        this.replay = replay;
        this.presence = presence;
    }

    /**
     * A board stream. With {@code lastEventId}: what came after it, or {@code event: resync} when the
     * buffer no longer has it. Without: a {@code ready} frame, so the client knows the stream is live.
     */
    public SseEmitter openProject(UUID projectId, UUID userId, @Nullable String lastEventId) {
        // no timeout (0): the heartbeat keeps it alive, and the browser closes it
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = hub.subscribeProject(projectId, userId, emitter);
        List<Frame> first = lastEventId == null || lastEventId.isBlank()
                ? List.of(new Frame(null, "ready", "{}"))
                : replay.since(projectId, lastEventId).orElseGet(() -> List.of(new Frame(null, "resync", "{}")));
        subscriber.open(first);
        presence.join(projectId, userId);
        return emitter;
    }

    /** The user's own stream; notifications arrive here from phase 10. */
    public SseEmitter openUser(UUID userId) {
        SseEmitter emitter = new SseEmitter(0L);
        hub.subscribeUser(userId, emitter).open(List.of(new Frame(null, "ready", "{}")));
        return emitter;
    }
}

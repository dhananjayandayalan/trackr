package dev.trakr.api.realtime.internal;

import dev.trakr.api.realtime.internal.SseHub.Frame;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who is looking at a board: a Redis sorted set {@code presence:project:{id}} per project, member =
 * user id, score = when that entry expires. Each instance refreshes the entries of its own viewers on
 * every heartbeat, so a viewer whose instance died drops out within {@link #TTL} by itself — nobody has
 * to clean up after a crash.
 *
 * <p>The set is shared by all instances; each one compares it with what it last told its own viewers
 * and sends a {@code presence} frame when it changed. A join or leave on this instance is sent at once,
 * one on another instance within a heartbeat.
 */
@Component
class PresenceTracker {

    static final Duration TTL = Duration.ofSeconds(45);

    private final StringRedisTemplate redis;
    private final SseHub hub;
    private final JsonMapper json;
    private final Clock clock;
    private final Map<UUID, Set<String>> lastSent = new ConcurrentHashMap<>();

    PresenceTracker(StringRedisTemplate redis, SseHub hub, JsonMapper json, Clock clock) {
        this.redis = redis;
        this.hub = hub;
        this.json = json;
        this.clock = clock;
        hub.onLeave(this::leave);
    }

    void join(UUID projectId, UUID userId) {
        redis.opsForZSet().add(key(projectId), userId.toString(), expiry());
        publishIfChanged(projectId);
    }

    // the same user may have the board open in two tabs: only the last one leaving takes them off
    private void leave(UUID projectId, UUID userId) {
        if (!hub.isWatching(projectId, userId)) {
            redis.opsForZSet().remove(key(projectId), userId.toString());
        }
        publishIfChanged(projectId);
    }

    @Scheduled(fixedRate = 15_000)
    void refresh() {
        hub.viewers().forEach((projectId, users) -> {
            users.forEach(userId -> redis.opsForZSet().add(key(projectId), userId.toString(), expiry()));
            publishIfChanged(projectId);
        });
    }

    private void publishIfChanged(UUID projectId) {
        String key = key(projectId);
        // drop entries whose instance stopped refreshing them
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, clock.millis());
        Set<String> members = new TreeSet<>(redis.opsForZSet().range(key, 0, -1));
        Set<String> previous = lastSent.put(projectId, members);
        if (!members.equals(previous) && hub.subscriberCount(projectId) > 0) {
            // no id: presence is "now", there is nothing to replay after a reconnect
            hub.broadcast(projectId, new Frame(null, "presence",
                    json.writeValueAsString(Map.of("userIds", List.copyOf(members)))));
        }
        if (hub.subscriberCount(projectId) == 0) {
            lastSent.remove(projectId);
        }
    }

    private double expiry() {
        return clock.millis() + TTL.toMillis();
    }

    private static String key(UUID projectId) {
        return "presence:project:" + projectId;
    }
}

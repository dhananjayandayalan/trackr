package dev.trakr.collab.room;

import dev.trakr.collab.config.CollabInstance;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Which collab instances have a member in a room right now, so exactly one of them takes its checkpoints
 * (06-collab §6). Two instances checkpointing the same room wouldn't corrupt anything — the second snapshot is
 * just ignored or redundant — but would double the work for the browsers.
 *
 * <p>Redis key {@code collab:room:{issueId}}, a sorted set: member = instance id, score = when its claim expires.
 * Every instance renews its claims every 10 s; one that dies simply stops renewing and drops out after 30 s. Sets
 * can't expire single members, so the expiry time is the score and readers remove what is past it.
 */
@Component
public class RoomPresence {

    private static final Logger log = LoggerFactory.getLogger(RoomPresence.class);
    static final Duration CLAIM = Duration.ofSeconds(30);
    static final Duration KEY_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final RoomRegistry rooms;
    private final String instanceId;
    private final Clock clock;

    RoomPresence(StringRedisTemplate redis, RoomRegistry rooms, CollabInstance instance, Clock clock) {
        this.redis = redis;
        this.rooms = rooms;
        this.instanceId = instance.id();
        this.clock = clock;
    }

    static String key(UUID issueId) {
        return "collab:room:" + issueId;
    }

    /** "I have a member in this room" — on a member's join and on every heartbeat. */
    public void announce(UUID issueId) {
        try {
            redis.opsForZSet().add(key(issueId), instanceId, clock.millis() + CLAIM.toMillis());
            redis.expire(key(issueId), KEY_TTL);
        } catch (DataAccessException e) {
            log.warn("could not announce room {}: {}", issueId, e.getMessage());
        }
    }

    /** The last member here left. */
    public void withdraw(UUID issueId) {
        try {
            redis.opsForZSet().remove(key(issueId), instanceId);
        } catch (DataAccessException e) {
            log.warn("could not withdraw from room {}: {}", issueId, e.getMessage());
        }
    }

    /**
     * True when this is the lowest live instance id in the room. Also true when the set is empty or Redis is
     * unreachable: then this instance can't know better, and a checkpoint too many is harmless — none at all would
     * let collab_update grow forever.
     */
    public boolean isLeader(UUID issueId) {
        try {
            redis.opsForZSet().removeRangeByScore(key(issueId), 0, clock.millis());
            Set<String> live = redis.opsForZSet().range(key(issueId), 0, -1);
            if (live == null || live.isEmpty()) {
                return true;
            }
            return live.stream().min(Comparator.naturalOrder()).orElseThrow().equals(instanceId);
        } catch (DataAccessException e) {
            log.warn("could not read room presence for {}: {}", issueId, e.getMessage());
            return true;
        }
    }

    @Scheduled(fixedDelay = 10_000)
    void heartbeat() {
        for (Room room : rooms.all()) {
            if (room.hasMember()) {
                announce(room.issueId());
            }
        }
    }
}

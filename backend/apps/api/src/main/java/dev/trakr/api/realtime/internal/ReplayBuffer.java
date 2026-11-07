package dev.trakr.api.realtime.internal;

import dev.trakr.api.realtime.internal.SseHub.Frame;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The last few minutes of each project's stream, in a Redis Stream {@code sse:project:{id}}, so a
 * browser that lost its connection gets what it missed instead of refetching the board (03-api §4).
 * Shared by all api instances: the browser may reconnect to a different one.
 */
@Component
public class ReplayBuffer {

    static final long MAX_ENTRIES = 2000;
    static final Duration WINDOW = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    ReplayBuffer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * {@code XADD sse:project:{id} MAXLEN ~ 2000 * id … event … data …}. "~" lets Redis trim in whole
     * blocks, which is much cheaper than exactly 2000. The key expires 5 minutes after the last event,
     * so a quiet project's buffer disappears and a late reconnect gets "resync".
     */
    public void append(UUID projectId, Frame frame) {
        String key = key(projectId);
        MapRecord<String, String, String> record = StreamRecords.newRecord().in(key)
                .ofMap(Map.of("id", String.valueOf(frame.id()), "event", frame.name(), "data", frame.data()));
        redis.opsForStream().add(record, XAddOptions.trim(TrimOptions.maxLen(MAX_ENTRIES).approximate()));
        redis.expire(key, WINDOW);
    }

    /**
     * The frames after the one with this envelope id ({@code XRANGE} over the buffer), or empty when that
     * id is no longer (or never was) in the buffer — the client must refetch ("resync").
     */
    public Optional<List<Frame>> since(UUID projectId, String lastEventId) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(key(projectId), Range.unbounded());
        if (records == null) {
            return Optional.empty();
        }
        for (int i = 0; i < records.size(); i++) {
            if (lastEventId.equals(records.get(i).getValue().get("id"))) {
                return Optional.of(records.subList(i + 1, records.size()).stream()
                        .map(record -> new Frame(String.valueOf(record.getValue().get("id")),
                                String.valueOf(record.getValue().get("event")),
                                String.valueOf(record.getValue().get("data"))))
                        .toList());
            }
        }
        return Optional.empty();
    }

    private static String key(UUID projectId) {
        return "sse:project:" + projectId;
    }
}

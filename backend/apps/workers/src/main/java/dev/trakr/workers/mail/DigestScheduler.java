package dev.trakr.workers.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every 10 minutes: one email per user with everything that piled up in {@code digest:{userId}}.
 *
 * <p>The list is first renamed to a key only this run knows: anything the Mailer pushes meanwhile goes
 * into a fresh list for the next digest, and two workers instances can't send the same entries — only
 * one RENAME of a key succeeds.
 */
@Component
class DigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);
    private static final String PREFIX = "digest:";

    private final StringRedisTemplate redis;
    private final Mailer mailer;
    private final JsonMapper json;

    DigestScheduler(StringRedisTemplate redis, Mailer mailer, JsonMapper json) {
        this.redis = redis;
        this.mailer = mailer;
        this.json = json;
    }

    static String key(UUID userId) {
        return PREFIX + userId;
    }

    @Scheduled(fixedDelayString = "${trakr.mail.digest-interval:10m}", initialDelayString = "${trakr.mail.digest-interval:10m}")
    void sendDigests() {
        List<String> keys = new ArrayList<>();
        // SCAN, not KEYS: it walks the keyspace in small steps instead of blocking Redis while it lists everything
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(PREFIX + "*").count(500).build())) {
            cursor.forEachRemaining(keys::add);
        }
        keys.forEach(this::sendDigest);
    }

    void sendDigest(String key) {
        UUID userId = UUID.fromString(key.substring(PREFIX.length()));
        String sending = "digest-sending:" + userId + ":" + UUID.randomUUID();
        if (!Boolean.TRUE.equals(redis.renameIfAbsent(key, sending))) {
            return;
        }
        List<String> entries = redis.opsForList().range(sending, 0, -1);
        if (entries == null || entries.isEmpty()) {
            redis.delete(sending);
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (String entry : entries) {
            Map<?, ?> item = json.readValue(entry, Map.class);
            lines.append("- ").append(item.get("issueKey")).append(' ').append(item.get("summary")).append(" (")
                    .append(String.valueOf(item.get("kind")).replace('_', ' ')).append(" by ").append(item.get("actorName"))
                    .append(")\n");
        }
        mailer.recipient(userId).ifPresent(to -> mailer.send(to, "digest", Map.of(
                "userName", to.name(), "count", String.valueOf(entries.size()), "lines", lines.toString().strip())));
        redis.delete(sending);
        log.info("digest sent userId={} entries={}", userId, entries.size());
    }
}

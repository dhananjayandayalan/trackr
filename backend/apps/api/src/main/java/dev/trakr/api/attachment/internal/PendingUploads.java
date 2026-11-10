package dev.trakr.api.attachment.internal;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Pending uploads in Redis: {@code upload:{uploadId}} → a hash of the PendingUpload fields, gone after
 * {@link #TTL}. Redis, not a table: a pending upload is short-lived and disposable — if the browser never
 * confirms (tab closed, upload failed), the key simply expires; nothing has to clean up rows.
 */
@Component
public class PendingUploads {

    /** Twice the presigned URL's lifetime: an upload started at the last second still has time to finish. */
    static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    PendingUploads(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void remember(UUID uploadId, PendingUpload upload) {
        String key = key(uploadId);
        redis.opsForHash().putAll(key, Map.of(
                "issueId", upload.issueId().toString(),
                "objectKey", upload.objectKey(),
                "fileName", upload.fileName(),
                "contentType", upload.contentType(),
                "sizeBytes", Long.toString(upload.sizeBytes()),
                "userId", upload.userId().toString()));
        redis.expire(key, TTL);
    }

    public Optional<PendingUpload> find(UUID uploadId) {
        Map<Object, Object> hash = redis.opsForHash().entries(key(uploadId));
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PendingUpload(
                UUID.fromString(String.valueOf(hash.get("issueId"))),
                String.valueOf(hash.get("objectKey")),
                String.valueOf(hash.get("fileName")),
                String.valueOf(hash.get("contentType")),
                Long.parseLong(String.valueOf(hash.get("sizeBytes"))),
                UUID.fromString(String.valueOf(hash.get("userId")))));
    }

    /** Confirmed: the key goes, so the same upload can't be confirmed again. */
    public void forget(UUID uploadId) {
        redis.delete(key(uploadId));
    }

    private static String key(UUID uploadId) {
        return "upload:" + uploadId;
    }
}

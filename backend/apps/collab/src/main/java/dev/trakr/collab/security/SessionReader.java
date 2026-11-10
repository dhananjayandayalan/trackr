package dev.trakr.collab.security;

import dev.trakr.collab.config.CollabProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Who a {@code TRAKR_SESSION} cookie belongs to, read straight from the api's Spring Session data in Redis
 * (06-collab §5, 09-security §1). Read only: collab never creates, renews or changes a session.
 *
 * <p>BLOCK 1: what the api stores. Spring Session's indexed Redis repository keeps one hash per session at
 * {@code trakr:session:sessions:<id>}, with {@code lastAccessedTime} (Long, ms), {@code maxInactiveInterval}
 * (Integer, s) and one {@code sessionAttr:<name>} field per attribute, each value Java-serialised. The api puts the
 * user id into the attribute {@code FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME} at every login.
 *
 * <p>Why not load the whole session (Spring Session's {@code SessionRepository}) and read its SecurityContext?
 * Deserialising that needs the api's TrakrPrincipal class, Spring Security and the OAuth2 classes on collab's
 * classpath — one app's internals inside another. Three fields of JDK types are all collab needs.
 */
@Component
public class SessionReader {

    private static final Logger log = LoggerFactory.getLogger(SessionReader.class);

    // the constant's value (org.springframework.session.FindByIndexNameSessionRepository), spelled out: collab
    // doesn't depend on Spring Session for one string
    static final String PRINCIPAL_NAME_ATTRIBUTE =
            "org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME";
    private static final byte[] LAST_ACCESSED = bytes("lastAccessedTime");
    private static final byte[] MAX_INACTIVE = bytes("maxInactiveInterval");
    private static final byte[] PRINCIPAL = bytes("sessionAttr:" + PRINCIPAL_NAME_ATTRIBUTE);

    // BLOCK 2: Java deserialisation is a classic remote-code-execution hole: a crafted stream can build any object
    // graph on the classpath. Redis is our own, but the filter costs nothing — only these classes, a tiny graph.
    private static final ObjectInputFilter ONLY_SIMPLE_VALUES = ObjectInputFilter.Config.createFilter(
            "java.lang.String;java.lang.Long;java.lang.Integer;java.lang.Number;maxdepth=2;maxbytes=4096;!*");

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Clock clock;

    SessionReader(StringRedisTemplate redis, CollabProperties properties, Clock clock) {
        this.redis = redis;
        this.keyPrefix = properties.session().namespace() + ":sessions:";
        this.clock = clock;
    }

    // BLOCK 3: cookie value → session id → user id. Spring Session's cookie holds the id base64-encoded.
    public Optional<UUID> userId(String cookieValue) {
        String sessionId;
        try {
            sessionId = new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        byte[] key = bytes(keyPrefix + sessionId);
        List<byte[]> values;
        try {
            values = redis.execute((RedisCallback<List<byte[]>>) connection -> fields(connection, key));
        } catch (DataAccessException e) {
            log.warn("could not read the session from Redis: {}", e.getMessage());
            return Optional.empty();
        }
        if (values == null || values.size() != 3) {
            return Optional.empty();
        }
        Long lastAccessed = read(values.get(0), Long.class);
        Integer maxInactive = read(values.get(1), Integer.class);
        String principal = read(values.get(2), String.class);
        if (lastAccessed == null || maxInactive == null || principal == null) {
            return Optional.empty();
        }
        // BLOCK 4: expiry, exactly as Spring Session decides it. The Redis key itself outlives the session by a
        // few minutes (the api's cleanup needs it), so "the key exists" doesn't mean "the session is valid".
        if (maxInactive >= 0 && Instant.ofEpochMilli(lastAccessed).plusSeconds(maxInactive).isBefore(clock.instant())) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(principal));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static @Nullable List<byte[]> fields(RedisConnection connection, byte[] key) {
        return connection.hashCommands().hMGet(key, LAST_ACCESSED, MAX_INACTIVE, PRINCIPAL);
    }

    private static <T> @Nullable T read(byte @Nullable [] serialized, Class<T> type) {
        if (serialized == null) {
            return null;
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            in.setObjectInputFilter(ONLY_SIMPLE_VALUES);
            Object value = in.readObject();
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (InvalidClassException e) {
            log.warn("session attribute rejected by the deserialisation filter: {}", e.getMessage());
            return null;
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

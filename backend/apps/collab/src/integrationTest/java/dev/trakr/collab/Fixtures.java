package dev.trakr.collab;

import dev.trakr.kernel.Ids;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rows and sessions the api would have written, made directly: collab only reads them. Every {@link #world} is a
 * fresh workspace with a private project and one issue, so tests never see each other's rooms.
 */
public final class Fixtures {

    public static final String ORIGIN = "http://localhost:5173";

    /** Ada and Grace are members of the project, Vic is a viewer, Eve is in the workspace but not the project. */
    public record World(UUID workspace, UUID project, String key, UUID issueId, String issueKey, UUID ada,
            UUID grace, UUID vic, UUID eve) {
    }

    private Fixtures() {
    }

    public static World world(JdbcClient jdbc) {
        String key = randomLetters();
        World w = new World(Ids.newId(), Ids.newId(), key, Ids.newId(), key + "-1", Ids.newId(), Ids.newId(),
                Ids.newId(), Ids.newId());
        UUID status = Ids.newId();
        jdbc.sql("INSERT INTO workspace (id, slug, name) VALUES (?, ?, 'Test')")
                .params(w.workspace(), "ws-" + w.workspace()).update();
        for (UUID user : new UUID[] {w.ada(), w.grace(), w.vic(), w.eve()}) {
            jdbc.sql("""
                    INSERT INTO app_user (id, email, display_name, initials, avatar_color)
                    VALUES (?, ?, 'Someone', 'SO', 'pink')""").params(user, "u-" + user + "@example.com").update();
            jdbc.sql("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, 'member')")
                    .params(w.workspace(), user).update();
        }
        jdbc.sql("""
                INSERT INTO project (id, workspace_id, key, name, template, lead_id, access, color)
                VALUES (?, ?, ?, 'Project', 'kanban', ?, 'private', 'cyan')""")
                .params(w.project(), w.workspace(), key, w.ada()).update();
        jdbc.sql("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'admin'), (?, ?, 'member'), (?, ?, 'viewer')")
                .params(w.project(), w.ada(), w.project(), w.grace(), w.project(), w.vic()).update();
        jdbc.sql("""
                INSERT INTO workflow_status (id, project_id, name, category, position, color)
                VALUES (?, ?, 'To do', 'todo', 0, 'todo')""").params(status, w.project()).update();
        jdbc.sql("""
                INSERT INTO issue (id, workspace_id, project_id, number, key, type, summary, status_id, status_category,
                                   reporter_id, rank)
                VALUES (?, ?, ?, 1, ?, 'task', 'Edit me', ?, 'todo', ?, 'V')""")
                .params(w.issueId(), w.workspace(), w.project(), w.issueKey(), status, w.ada()).update();
        return w;
    }

    /**
     * A signed-in session, stored the way the api's Spring Session writes it (one Redis hash, JDK-serialised
     * values), and the TRAKR_SESSION cookie value for it (the id, base64).
     */
    public static String session(StringRedisTemplate redis, UUID userId) {
        String id = UUID.randomUUID().toString();
        Map<byte[], byte[]> hash = Map.of(
                bytes("creationTime"), serialize(System.currentTimeMillis()),
                bytes("lastAccessedTime"), serialize(System.currentTimeMillis()),
                bytes("maxInactiveInterval"), serialize(3600),
                bytes("sessionAttr:org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME"),
                serialize(userId.toString()));
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.hashCommands().hMSet(bytes("trakr:session:sessions:" + id), hash);
            return null;
        });
        return Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    /** Makes an existing session look idle for longer than it may be. */
    public static void expire(StringRedisTemplate redis, String cookie) {
        String id = new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.hashCommands().hSet(bytes("trakr:session:sessions:" + id), bytes("lastAccessedTime"),
                    serialize(System.currentTimeMillis() - Duration.ofHours(2).toMillis()));
            return null;
        });
    }

    public static byte[] fixture(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static byte[] serialize(Serializable value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String randomLetters() {
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            letters.append((char) ('A' + (int) (Math.random() * 26)));
        }
        return letters.toString();
    }
}

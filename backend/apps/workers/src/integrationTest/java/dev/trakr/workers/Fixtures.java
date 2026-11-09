package dev.trakr.workers;

import dev.trakr.contracts.events.IssueSnapshot;
import dev.trakr.contracts.events.SprintSnapshot;
import dev.trakr.kernel.Ids;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rows the api would have written, inserted directly (the workers only read them): a workspace with two
 * people, a scrum project with To do / Done, an active sprint. Every call makes a fresh, unique set.
 */
public final class Fixtures {

    public record World(UUID workspace, UUID ada, UUID grace, UUID project, String key, UUID todo, UUID done,
            UUID sprint) {
    }

    private Fixtures() {
    }

    public static World world(JdbcClient jdbc) {
        String suffix = Ids.newId().toString().substring(24);
        // project keys are unique per workspace, and every world has a fresh workspace
        String key = randomLetters();
        World w = new World(Ids.newId(), Ids.newId(), Ids.newId(), Ids.newId(), key, Ids.newId(), Ids.newId(),
                Ids.newId());
        jdbc.sql("INSERT INTO workspace (id, slug, name) VALUES (?, ?, 'Test')").params(w.workspace(), "ws-" + suffix).update();
        user(jdbc, w.ada(), "Ada Lovelace", "AL");
        user(jdbc, w.grace(), "Grace Hopper", "GH");
        jdbc.sql("""
                INSERT INTO project (id, workspace_id, key, name, template, lead_id, access, color)
                VALUES (?, ?, ?, 'Project', 'scrum', ?, 'private', 'cyan')""")
                .params(w.project(), w.workspace(), key, w.ada()).update();
        jdbc.sql("""
                INSERT INTO workflow_status (id, project_id, name, category, position, color)
                VALUES (?, ?, 'To do', 'todo', 0, 'todo'), (?, ?, 'Done', 'done', 1, 'done')""")
                .params(w.todo(), w.project(), w.done(), w.project()).update();
        jdbc.sql("""
                INSERT INTO sprint (id, project_id, name, state, starts_on, ends_on, position, started_at)
                VALUES (?, ?, 'Sprint 1', 'active', DATE '2026-11-09', DATE '2026-11-23', 0, now())""")
                .params(w.sprint(), w.project()).update();
        return w;
    }

    private static String randomLetters() {
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            letters.append((char) ('A' + (int) (Math.random() * 26)));
        }
        return letters.toString();
    }

    private static void user(JdbcClient jdbc, UUID id, String name, String initials) {
        jdbc.sql("INSERT INTO app_user (id, email, display_name, initials, avatar_color) VALUES (?, ?, ?, ?, 'pink')")
                .params(id, "u-" + id + "@example.com", name, initials).update();
    }

    /** An issue row in the sprint (or the backlog), watched by its reporter (Ada). */
    public static IssueSnapshot issue(JdbcClient jdbc, World w, long number, String summary, @Nullable BigDecimal points,
            boolean inSprint) {
        IssueSnapshot s = snapshot(w, Ids.newId(), number, summary, points, inSprint ? w.sprint() : null, false, 0);
        jdbc.sql("""
                INSERT INTO issue (id, workspace_id, project_id, number, key, type, summary, status_id, status_category,
                                   reporter_id, sprint_id, story_points, rank, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'task', ?, ?, 'todo', ?, ?, ?, ?, ?, ?)""")
                .params(s.id(), w.workspace(), w.project(), number, s.key(), summary, w.todo(), w.ada(), s.sprintId(),
                        points, s.rank(), Timestamp.from(s.createdAt()), Timestamp.from(s.updatedAt()))
                .update();
        jdbc.sql("INSERT INTO watcher (issue_id, user_id) VALUES (?, ?)").params(s.id(), w.ada()).update();
        return s;
    }

    /** Marks the issue done in Postgres, as the api's transition would, and returns the event's snapshot. */
    public static IssueSnapshot markDone(JdbcClient jdbc, World w, IssueSnapshot s, long version) {
        jdbc.sql("UPDATE issue SET status_id = ?, status_category = 'done', resolved_at = now(), version = ? WHERE id = ?")
                .params(w.done(), version, s.id()).update();
        return snapshot(w, s.id(), s.number(), s.summary(), s.storyPoints(), s.sprintId(), true, version);
    }

    public static IssueSnapshot snapshot(World w, UUID id, long number, String summary, @Nullable BigDecimal points,
            @Nullable UUID sprintId, boolean done, long version) {
        Instant now = Instant.parse("2026-11-09T09:00:00Z");
        return new IssueSnapshot(id, w.workspace(), w.project(), w.key(), w.key() + "-" + number, number, "task",
                summary, null, done ? w.done() : w.todo(), done ? "done" : "todo", "medium", null, w.ada(), null,
                sprintId, points, "V" + number, List.of(), null, null, false, null, 0, null, done ? now : null, false,
                version, now, now);
    }

    public static SprintSnapshot sprint(World w, String name, String state) {
        return new SprintSnapshot(w.sprint(), w.workspace(), w.project(), name, null, state, LocalDate.parse("2026-11-09"),
                LocalDate.parse("2026-11-23"), 0, null, null, null, 1);
    }

    /** Polls until the condition holds; consumers are asynchronous. */
    public static void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}

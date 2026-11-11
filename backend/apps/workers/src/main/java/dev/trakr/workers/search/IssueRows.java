package dev.trakr.workers.search;

import dev.trakr.contracts.events.IssueSnapshot;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every live issue straight from Postgres, as snapshots — what the events would have carried. For the
 * reindex: Postgres is the source of truth, the index can always be rebuilt from it (05-search §1).
 */
@Component
class IssueRows {

    private final JdbcTemplate jdbc;

    IssueRows(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        // with a fetch size inside a transaction, the Postgres driver reads through a cursor, 1000 rows at a
        // time; without one it would load every issue into memory before returning the first
        this.jdbc.setFetchSize(1000);
    }

    @Transactional(readOnly = true)
    public long forEach(Consumer<IssueSnapshot> action) {
        try (Stream<IssueSnapshot> rows = jdbc.queryForStream("""
                SELECT * FROM issue WHERE deleted_at IS NULL ORDER BY project_id, number""", (rs, n) -> snapshot(rs))) {
            long[] count = {0};
            rows.forEach(snapshot -> {
                action.accept(snapshot);
                count[0]++;
            });
            return count[0];
        }
    }

    /** One project's live issues (phase 17: re-index them after a workflow change renamed or remapped statuses). */
    @Transactional(readOnly = true)
    public long forEachInProject(UUID projectId, Consumer<IssueSnapshot> action) {
        try (Stream<IssueSnapshot> rows = jdbc.queryForStream(
                "SELECT * FROM issue WHERE deleted_at IS NULL AND project_id = ? ORDER BY number",
                (rs, n) -> snapshot(rs), projectId)) {
            long[] count = {0};
            rows.forEach(snapshot -> {
                action.accept(snapshot);
                count[0]++;
            });
            return count[0];
        }
    }

    private static IssueSnapshot snapshot(ResultSet rs) throws SQLException {
        String key = rs.getString("key");
        return new IssueSnapshot(
                uuid(rs, "id"), uuid(rs, "workspace_id"), uuid(rs, "project_id"),
                key.substring(0, key.lastIndexOf('-')), key, rs.getLong("number"), rs.getString("type"),
                rs.getString("summary"), rs.getString("description_text"), uuid(rs, "status_id"),
                rs.getString("status_category"), rs.getString("priority"), uuid(rs, "assignee_id"),
                uuid(rs, "reporter_id"), uuid(rs, "parent_id"), uuid(rs, "sprint_id"), rs.getBigDecimal("story_points"),
                rs.getString("rank"), labels(rs.getArray("labels")), date(rs, "due_date"), date(rs, "start_date"),
                rs.getBoolean("flagged"), (Integer) rs.getObject("original_estimate_min"), rs.getInt("time_spent_min"),
                rs.getString("fix_version"), instant(rs, "resolved_at"), false, rs.getLong("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static @Nullable UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static @Nullable LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static List<String> labels(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}

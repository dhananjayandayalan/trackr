package dev.trakr.workers.search;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.trakr.contracts.events.IssueSnapshot;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Names for documents (05-search §5): the snapshot carries ids, the index wants names too. Read from
 * Postgres — the workers may read every table (02-data-model §6) — and kept a few minutes in Caffeine,
 * an in-process cache, so a batch of 500 events about the same project costs a handful of queries.
 * Sprint events evict their sprint; other names are allowed to be a few minutes stale.
 */
@Component
public class NameLookup {

    private record Named(@Nullable String name) {
    }

    record Sprint(String name, String state) {
    }

    record Epic(UUID id, String name) {
    }

    private final JdbcClient jdbc;
    private final Cache<UUID, Named> statuses = cache();
    private final Cache<UUID, Named> users = cache();
    private final Cache<UUID, Optional<Sprint>> sprints = cache();
    private final Cache<UUID, Optional<Epic>> epics = cache();

    NameLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static <K, V> Cache<K, V> cache() {
        return Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();
    }

    /** One context per snapshot; the watchers of all of them in one query. */
    public Map<UUID, IssueContext> contextsFor(Collection<IssueSnapshot> snapshots) {
        Map<UUID, List<UUID>> watchers = watchersOf(snapshots.stream().map(IssueSnapshot::id).toList());
        Map<UUID, IssueContext> contexts = new LinkedHashMap<>();
        for (IssueSnapshot s : snapshots) {
            Optional<Epic> epic = s.parentId() == null ? Optional.empty() : epicOf(s.parentId());
            Optional<Sprint> sprint = s.sprintId() == null ? Optional.empty() : sprintOf(s.sprintId());
            contexts.put(s.id(), new IssueContext(
                    statuses.get(s.statusId(), id -> new Named(name("SELECT name FROM workflow_status WHERE id = ?", id))).name(),
                    s.assigneeId() == null ? null : userName(s.assigneeId()),
                    epic.map(Epic::id).orElse(null),
                    epic.map(Epic::name).orElse(null),
                    sprint.map(Sprint::name).orElse(null),
                    sprint.map(Sprint::state).orElse(null),
                    watchers.getOrDefault(s.id(), List.of())));
        }
        return contexts;
    }

    public @Nullable String userName(UUID userId) {
        return users.get(userId, id -> new Named(name("SELECT display_name FROM app_user WHERE id = ?", id))).name();
    }

    public void evictSprint(UUID sprintId) {
        sprints.invalidate(sprintId);
    }

    private Optional<Sprint> sprintOf(UUID sprintId) {
        return sprints.get(sprintId, id -> jdbc.sql("SELECT name, state FROM sprint WHERE id = ?").param(id)
                .query((rs, n) -> new Sprint(rs.getString("name"), rs.getString("state"))).optional());
    }

    // a story's parent is its epic; a subtask's epic is its parent's parent
    private Optional<Epic> epicOf(UUID parentId) {
        return epics.get(parentId, id -> jdbc.sql("""
                        SELECT CASE WHEN p.type = 'epic' THEN p.id ELSE e.id END AS epic_id,
                               CASE WHEN p.type = 'epic' THEN p.summary ELSE e.summary END AS epic_name
                        FROM issue p LEFT JOIN issue e ON e.id = p.parent_id AND e.type = 'epic'
                        WHERE p.id = ?""")
                .param(id)
                .query((rs, n) -> new Object[] {rs.getObject("epic_id", UUID.class), rs.getString("epic_name")})
                .optional()
                // a story without an epic, or a subtask whose story has none
                .filter(row -> row[0] != null)
                .map(row -> new Epic((UUID) row[0], (String) row[1])));
    }

    private Map<UUID, List<UUID>> watchersOf(List<UUID> issueIds) {
        if (issueIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<UUID>> watchers = new HashMap<>();
        jdbc.sql("SELECT issue_id, array_agg(user_id) AS users FROM watcher WHERE issue_id = ANY(?) GROUP BY issue_id")
                .param(issueIds.toArray(UUID[]::new))
                .query(rs -> {
                    watchers.put(rs.getObject("issue_id", UUID.class), uuids(rs.getArray("users")));
                });
        return watchers;
    }

    private @Nullable String name(String sql, UUID id) {
        return jdbc.sql(sql).param(id).query(String.class).optional().orElse(null);
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return Arrays.stream((Object[]) array.getArray()).map(UUID.class::cast).sorted().toList();
    }
}

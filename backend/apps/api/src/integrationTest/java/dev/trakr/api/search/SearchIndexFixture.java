package dev.trakr.api.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Stands in for the workers' indexer: the search tests can't run the workers app, so this builds the
 * same documents from Postgres (as workers' reindex does) into a fresh index under the test alias. The
 * mapping is workers' own file (Gradle passes its path), so the tests query exactly the production
 * mapping and analyzers.
 */
final class SearchIndexFixture {

    private static final String INDEX = "trakr-issues-api-it-v1";

    private final ElasticsearchClient elasticsearch;
    private final JdbcClient jdbc;
    private final String alias;

    SearchIndexFixture(ElasticsearchClient elasticsearch, JdbcClient jdbc, String alias) {
        this.elasticsearch = elasticsearch;
        this.jdbc = jdbc;
        this.alias = alias;
    }

    /** Drops and refills the index from the current database; returns how many issues it indexed. */
    int rebuild() throws IOException {
        if (elasticsearch.indices().exists(e -> e.index(INDEX)).value()) {
            elasticsearch.indices().delete(d -> d.index(INDEX));
        }
        try (InputStream mapping = Files.newInputStream(Path.of(System.getProperty("trakr.search.mapping")))) {
            elasticsearch.indices().create(c -> c.index(INDEX).withJson(mapping)
                    .aliases(alias, a -> a));
        }
        List<Map<String, Object>> documents = jdbc.sql("""
                SELECT i.id, i.workspace_id, i.project_id, p.key AS project_key, i.key, i.number, i.type, i.priority,
                       i.status_category, i.status_id, ws.name AS status_name, i.summary, i.description_text,
                       i.labels, i.assignee_id, i.reporter_id, u.display_name AS assignee_name, i.parent_id,
                       CASE WHEN parent.type = 'epic' THEN parent.id END AS epic_id,
                       CASE WHEN parent.type = 'epic' THEN parent.summary END AS epic_name,
                       i.sprint_id, s.name AS sprint_name, s.state AS sprint_state, i.story_points, i.flagged,
                       ARRAY(SELECT w.user_id::text FROM watcher w WHERE w.issue_id = i.id) AS watcher_ids,
                       i.created_at, i.updated_at, i.resolved_at, i.due_date, i.start_date, i.rank
                FROM issue i
                JOIN project p ON p.id = i.project_id
                JOIN workflow_status ws ON ws.id = i.status_id
                LEFT JOIN app_user u ON u.id = i.assignee_id
                LEFT JOIN issue parent ON parent.id = i.parent_id
                LEFT JOIN sprint s ON s.id = i.sprint_id
                WHERE i.deleted_at IS NULL""").query().listOfRows().stream().map(SearchIndexFixture::document).toList();

        List<BulkOperation> operations = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            // routing = project id, like the indexer: one project's issues live on one shard
            operations.add(BulkOperation.of(op -> op.index(i -> i.index(alias).id((String) document.get("id"))
                    .routing((String) document.get("projectId")).document(document))));
        }
        BulkResponse response = elasticsearch.bulk(b -> b.operations(operations).refresh(Refresh.True));
        if (response.errors()) {
            throw new IllegalStateException("bulk indexing failed: " + response.items().stream()
                    .filter(item -> item.error() != null).findFirst().map(item -> item.error().reason()).orElse("?"));
        }
        return documents.size();
    }

    // snake_case columns → the index's camelCase fields, JDBC types → JSON-friendly values
    private static Map<String, Object> document(Map<String, Object> row) {
        Map<String, Object> document = new LinkedHashMap<>();
        row.forEach((column, value) -> {
            if (value != null) {
                document.put(camel(column), json(value));
            }
        });
        document.put("priorityRank", switch ((String) row.get("priority")) {
            case "highest" -> 5;
            case "high" -> 4;
            case "medium" -> 3;
            case "low" -> 2;
            default -> 1;
        });
        document.put("commentsText", "");
        document.put("mentionedUserIds", List.of());
        document.put("deleted", false);
        return document;
    }

    private static Object json(Object value) {
        try {
            return switch (value) {
                case Timestamp timestamp -> timestamp.toInstant().toString();
                case java.sql.Date date -> date.toLocalDate().toString();
                case Array array -> Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
                case java.util.UUID uuid -> uuid.toString();
                default -> value;
            };
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String camel(String column) {
        StringBuilder name = new StringBuilder();
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                name.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return name.toString();
    }
}

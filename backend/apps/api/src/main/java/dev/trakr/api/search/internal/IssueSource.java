package dev.trakr.api.search.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The part of an index document a result row needs; {@link #FIELDS} is sent as the _source filter, so
 * Elasticsearch doesn't ship descriptions and comment text we'd throw away. Field names match workers'
 * IssueDocument (and es/trakr-issues-v1.json).
 */
public record IssueSource(UUID id, String key, String projectKey, String summary, String type, String priority,
        @Nullable String statusName, String statusCategory, @Nullable UUID assigneeId, @Nullable String assigneeName,
        @Nullable String sprintName, @Nullable BigDecimal storyPoints, @Nullable List<String> labels, boolean flagged,
        @Nullable LocalDate dueDate, Instant createdAt, Instant updatedAt) {

    public static final List<String> FIELDS = List.of("id", "key", "projectKey", "summary", "type", "priority",
            "statusName", "statusCategory", "assigneeId", "assigneeName", "sprintName", "storyPoints", "labels",
            "flagged", "dueDate", "createdAt", "updatedAt");
}

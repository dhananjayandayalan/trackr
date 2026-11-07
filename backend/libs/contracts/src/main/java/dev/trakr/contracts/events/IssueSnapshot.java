package dev.trakr.contracts.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An issue's own columns at one version, carried by issue events so consumers never call back into
 * the api. Names that live in other tables (status, assignee, epic, sprint) are looked up by the
 * consumer that needs them: the search indexer reads those tables itself (02-data-model §6).
 * Schema evolution: add fields only (04-events §4).
 *
 * <p>Enumerations travel as their wire values ("story", "high", "in_progress"): contracts can't
 * depend on the api's enums.
 */
public record IssueSnapshot(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        String projectKey,
        String key,
        long number,
        String type,
        String summary,
        @Nullable String descriptionText,
        UUID statusId,
        String statusCategory,
        String priority,
        @Nullable UUID assigneeId,
        UUID reporterId,
        @Nullable UUID parentId,
        @Nullable UUID sprintId,
        @Nullable BigDecimal storyPoints,
        String rank,
        List<String> labels,
        @Nullable LocalDate dueDate,
        @Nullable LocalDate startDate,
        boolean flagged,
        @Nullable Integer originalEstimateMin,
        int timeSpentMin,
        @Nullable String fixVersion,
        @Nullable Instant resolvedAt,
        boolean deleted,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public IssueSnapshot {
        labels = List.copyOf(labels);
    }
}

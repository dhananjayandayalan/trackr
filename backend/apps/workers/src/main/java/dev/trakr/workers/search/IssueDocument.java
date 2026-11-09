package dev.trakr.workers.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One document of the {@code trakr-issues} index, field for field as in es/trakr-issues-v1.json
 * (05-search §2): everything search results and filters show, so the api never joins anything after a
 * search. The mapping is {@code dynamic: strict}, so a field added here but not there fails loudly.
 */
public record IssueDocument(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        String projectKey,
        String key,
        long number,
        String type,
        String priority,
        int priorityRank,
        String statusCategory,
        UUID statusId,
        @Nullable String statusName,
        String summary,
        @Nullable String descriptionText,
        String commentsText,
        List<String> labels,
        @Nullable UUID assigneeId,
        UUID reporterId,
        @Nullable String assigneeName,
        @Nullable UUID parentId,
        @Nullable UUID epicId,
        @Nullable String epicName,
        @Nullable UUID sprintId,
        @Nullable String sprintName,
        @Nullable String sprintState,
        @Nullable BigDecimal storyPoints,
        boolean flagged,
        List<UUID> watcherIds,
        List<UUID> mentionedUserIds,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant resolvedAt,
        @Nullable LocalDate dueDate,
        @Nullable LocalDate startDate,
        String rank,
        boolean deleted) {
}

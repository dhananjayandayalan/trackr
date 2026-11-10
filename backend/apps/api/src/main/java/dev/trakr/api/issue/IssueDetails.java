package dev.trakr.api.issue;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * GET /issues/{key}: everything the issue page shows, read in one SQL query (IssueQueries.detail).
 * {@code version} is what the ETag carries.
 */
public record IssueDetails(
        UUID id,
        String key,
        String projectKey,
        IssueType type,
        String summary,
        @Nullable String descriptionHtml,
        @Nullable String descriptionText,
        StatusRef status,
        Priority priority,
        @Nullable UserRef assignee,
        UserRef reporter,
        @Nullable IssueRef parent,
        @Nullable SprintRef sprint,
        @Nullable BigDecimal storyPoints,
        String rank,
        List<String> labels,
        @Nullable LocalDate dueDate,
        @Nullable LocalDate startDate,
        boolean flagged,
        @Nullable String fixVersion,
        TimeTracking timeTracking,
        List<IssueRef> subtasks,
        List<LinkView> links,
        boolean watching,
        int watcherCount,
        boolean starred,
        List<AttachmentView> attachments,
        @Nullable Instant resolvedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public record StatusRef(UUID id, String name, String category, String color) {
    }

    public record UserRef(UUID id, String displayName, String initials, String avatarColor) {
    }

    /** Another issue, as a parent, subtask or link target shows it. */
    public record IssueRef(UUID id, String key, String summary, IssueType type, String statusCategory,
            String statusName) {
    }

    @Schema(name = "IssueSprint")
    public record SprintRef(UUID id, String name, String state) {
    }

    /** {@code remainingMin}: estimate minus time spent, never below 0; null without an estimate. */
    public record TimeTracking(@Nullable Integer originalEstimateMin, int timeSpentMin, @Nullable Integer remainingMin) {

        public static TimeTracking of(@Nullable Integer originalEstimateMin, int timeSpentMin) {
            return new TimeTracking(originalEstimateMin, timeSpentMin,
                    originalEstimateMin == null ? null : Math.max(0, originalEstimateMin - timeSpentMin));
        }
    }

    /**
     * A file on the issue. The issue module reads the attachment table (read-only, like the workflow tables) so
     * the page needs one request; the attachment module owns the rows and returns the same record when it adds
     * one. The bytes: GET /attachments/{id}/content.
     */
    @Schema(name = "Attachment")
    public record AttachmentView(UUID id, String fileName, String contentType, long sizeBytes, UserRef uploader,
            Instant createdAt) {
    }

    /** {@code label} reads from this issue: "blocks" when it's the source, "is blocked by" when it's the target. */
    public record LinkView(UUID id, LinkType type, String label, IssueRef issue) {
    }
}

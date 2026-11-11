package dev.trakr.api.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The response records of /projects/{key}/reports/* (03-api §3.9). */
public final class ReportViews {

    private ReportViews() {
    }

    /**
     * One working day of a sprint. {@code remaining} is null for days still to come (the line stops at today);
     * {@code scopeChange} is the points added (positive) or removed (negative) since the previous working day,
     * null when the scope didn't change.
     */
    public record BurndownDay(LocalDate day, @Nullable BigDecimal remaining, BigDecimal ideal,
            @Nullable BigDecimal scopeChange) {
    }

    /** {@code committed}: the points in the sprint when it started; the ideal line runs from there to 0. */
    public record Burndown(UUID sprintId, String sprintName, String state, LocalDate startsOn, LocalDate endsOn,
            BigDecimal committed, List<BurndownDay> days) {
    }

    /** One closed sprint: what it committed to at the start and what was done at the end. */
    public record VelocityEntry(UUID sprintId, String name, BigDecimal committed, BigDecimal completed) {
    }

    public record ReportIssue(String key, String summary, String type, @Nullable BigDecimal storyPoints,
            String statusName, String statusCategory) {
    }

    /**
     * 00-product §3.10: completed / not completed / added mid-sprint. For the active sprint it is "so far",
     * computed from the issues now; for a closed one it is the snapshot taken when it closed.
     */
    public record SprintReportView(UUID sprintId, String name, String state, @Nullable LocalDate startsOn,
            @Nullable LocalDate endsOn, BigDecimal committedPoints, BigDecimal completedPoints, BigDecimal addedPoints,
            BigDecimal removedPoints, List<ReportIssue> completed, List<ReportIssue> notCompleted,
            List<ReportIssue> added) {
    }

    public record StatusSlice(UUID statusId, String name, String category, String color, int issues,
            BigDecimal points) {
    }

    /** {@code userId} null = unassigned. */
    public record AssigneeSlice(@Nullable UUID userId, String name, @Nullable String initials,
            @Nullable String avatarColor, int issues, BigDecimal points) {
    }

    /** {@code sprintId} null = the whole project (a project without sprints, or none active). */
    public record Breakdown(@Nullable UUID sprintId, List<StatusSlice> byStatus, List<AssigneeSlice> byAssignee) {
    }
}

package dev.trakr.api.sprint;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * GET /projects/{key}/backlog (03-api §3.7): the active sprint, the planned sprints in order, the
 * backlog, and the epics panel. Epics and subtasks are not list rows: epics have their own panel,
 * subtasks travel with their parent.
 */
public record BacklogView(@Nullable SprintList activeSprint, List<SprintList> plannedSprints, IssueList backlog,
        List<EpicSummary> epics) {

    public record SprintList(SprintView sprint, List<BacklogRow> issues, Points points) {
    }

    public record IssueList(List<BacklogRow> issues, Points points) {
    }

    /** Story points per status category: the coloured totals at the end of each list header. */
    public record Points(BigDecimal todo, BigDecimal inProgress, BigDecimal done) {

        public static Points of(List<BacklogRow> rows) {
            BigDecimal todo = BigDecimal.ZERO;
            BigDecimal inProgress = BigDecimal.ZERO;
            BigDecimal done = BigDecimal.ZERO;
            for (BacklogRow row : rows) {
                BigDecimal points = row.storyPoints() == null ? BigDecimal.ZERO : row.storyPoints();
                switch (row.statusCategory()) {
                    case "done" -> done = done.add(points);
                    case "in_progress" -> inProgress = inProgress.add(points);
                    default -> todo = todo.add(points);
                }
            }
            return new Points(todo, inProgress, done);
        }
    }

    public record BacklogRow(UUID id, String key, String summary, String type, String priority, UUID statusId,
            String statusName, String statusCategory, @Nullable UUID assigneeId, @Nullable BigDecimal storyPoints,
            @Nullable UUID epicId, String rank, boolean flagged, long version) {
    }

    /** {@code progressPct}: done children / all children, 0 for an epic without children. */
    public record EpicSummary(UUID id, String key, String summary, String color, int issueCount, int doneCount,
            int progressPct) {
    }
}

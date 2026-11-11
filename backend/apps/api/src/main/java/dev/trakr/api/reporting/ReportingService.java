package dev.trakr.api.reporting;

import dev.trakr.api.project.ProjectAccess;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.reporting.ReportViews.Breakdown;
import dev.trakr.api.reporting.ReportViews.Burndown;
import dev.trakr.api.reporting.ReportViews.ReportIssue;
import dev.trakr.api.reporting.ReportViews.SprintReportView;
import dev.trakr.api.reporting.ReportViews.VelocityEntry;
import dev.trakr.api.reporting.internal.BurndownMath;
import dev.trakr.api.reporting.internal.ReportQueries;
import dev.trakr.api.reporting.internal.ReportQueries.ClosedReport;
import dev.trakr.api.reporting.internal.ReportQueries.IssueRow;
import dev.trakr.api.reporting.internal.ReportQueries.SprintRow;
import dev.trakr.api.shared.web.TrakrException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reports screen (00-product §3.10, 03-api §3.9). Everything is read: burndown and velocity come from the
 * read models the workers maintain (sprint_day_snapshot, sprint_report); the sprint report of the active sprint
 * and the breakdowns are computed from the issues as they are now.
 */
@Service
public class ReportingService {

    /** 00-product §3.10: velocity looks at the last five closed sprints. */
    public static final int DEFAULT_VELOCITY_SPRINTS = 5;

    private final ReportQueries queries;
    private final ProjectAccess access;
    private final Clock clock;

    ReportingService(ReportQueries queries, ProjectAccess access, Clock clock) {
        this.queries = queries;
        this.access = access;
        this.clock = clock;
    }

    /**
     * Remaining points per working day against the ideal line. "Today" comes from the Clock (UTC, like the report
     * projector's day rows), so tests can fix it.
     */
    @Transactional(readOnly = true)
    public Burndown burndown(UUID userId, String projectKey, @Nullable UUID sprintId) {
        UUID projectId = access.require(userId, projectKey, ProjectRole.VIEWER).projectId();
        SprintRow sprint = sprintFor(projectId, sprintId);
        if (sprint.startsOn() == null || sprint.endsOn() == null) {
            throw new TrakrException.Conflict("SPRINT_NOT_STARTED", sprint.name() + " has not started yet.");
        }
        BigDecimal committed = sprint.committedPoints() == null ? BigDecimal.ZERO : sprint.committedPoints();
        return new Burndown(sprint.id(), sprint.name(), sprint.state(), sprint.startsOn(), sprint.endsOn(), committed,
                BurndownMath.days(sprint.startsOn(), sprint.endsOn(), committed, queries.snapshots(sprint.id()),
                        LocalDate.now(clock)));
    }

    @Transactional(readOnly = true)
    public List<VelocityEntry> velocity(UUID userId, String projectKey, int last) {
        UUID projectId = access.require(userId, projectKey, ProjectRole.VIEWER).projectId();
        return queries.velocity(projectId, Math.clamp(last, 1, 20));
    }

    /**
     * Completed / not completed / added. A closed sprint reads the report stored when it closed (the issues may
     * have moved on since); the active one is computed now, against what it committed to when it started.
     */
    @Transactional(readOnly = true)
    public SprintReportView sprintReport(UUID userId, String projectKey, @Nullable UUID sprintId) {
        UUID projectId = access.require(userId, projectKey, ProjectRole.VIEWER).projectId();
        SprintRow sprint = sprintFor(projectId, sprintId);
        Set<UUID> committedIds = new HashSet<>(sprint.committedIssueIds());
        BigDecimal committed = sprint.committedPoints() == null ? BigDecimal.ZERO : sprint.committedPoints();

        ClosedReport closed = queries.closedReport(sprint.id()).orElse(null);
        if (closed != null) {
            List<IssueRow> completed = queries.issuesWithIds(closed.completedIds());
            List<IssueRow> incomplete = queries.issuesWithIds(closed.incompleteIds());
            List<IssueRow> atEnd = new ArrayList<>(completed);
            atEnd.addAll(incomplete);
            return new SprintReportView(sprint.id(), sprint.name(), sprint.state(), sprint.startsOn(), sprint.endsOn(),
                    closed.committed(), closed.completed(), closed.added(), closed.removed(), issues(completed),
                    issues(incomplete), issues(atEnd.stream().filter(row -> !committedIds.contains(row.id())).toList()));
        }

        List<IssueRow> now = queries.issuesInSprint(sprint.id());
        List<IssueRow> done = now.stream().filter(row -> "done".equals(row.issue().statusCategory())).toList();
        List<IssueRow> open = now.stream().filter(row -> !"done".equals(row.issue().statusCategory())).toList();
        List<IssueRow> added = now.stream().filter(row -> !committedIds.contains(row.id())).toList();
        return new SprintReportView(sprint.id(), sprint.name(), sprint.state(), sprint.startsOn(), sprint.endsOn(),
                committed, points(done), points(added), queries.pointsLeftSprint(committedIds, sprint.id()),
                issues(done), issues(open), issues(added));
    }

    /** By status and by assignee: the named sprint, else the active one, else the whole project. */
    @Transactional(readOnly = true)
    public Breakdown breakdown(UUID userId, String projectKey, @Nullable UUID sprintId) {
        UUID projectId = access.require(userId, projectKey, ProjectRole.VIEWER).projectId();
        UUID scope = sprintId != null ? sprintFor(projectId, sprintId).id() : queries.activeSprintId(projectId).orElse(null);
        return new Breakdown(scope, queries.byStatus(projectId, scope), queries.byAssignee(projectId, scope));
    }

    // a sprint of another project is "no such sprint", the same 404 as a made-up id
    private SprintRow sprintFor(UUID projectId, @Nullable UUID sprintId) {
        return (sprintId == null ? queries.defaultSprint(projectId) : queries.sprint(projectId, sprintId))
                .orElseThrow(() -> new TrakrException.NotFound(sprintId == null
                        ? "This project has no active or closed sprint yet." : "No such sprint in this project."));
    }

    private static List<ReportIssue> issues(List<IssueRow> rows) {
        return rows.stream().map(IssueRow::issue).toList();
    }

    private static BigDecimal points(List<IssueRow> rows) {
        return rows.stream().map(row -> row.issue().storyPoints()).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

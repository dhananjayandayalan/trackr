package dev.trakr.api.reporting.internal;

import static dev.trakr.jooq.Tables.APP_USER;
import static dev.trakr.jooq.Tables.ISSUE;
import static dev.trakr.jooq.Tables.SPRINT;
import static dev.trakr.jooq.Tables.SPRINT_DAY_SNAPSHOT;
import static dev.trakr.jooq.Tables.SPRINT_REPORT;
import static dev.trakr.jooq.Tables.WORKFLOW_STATUS;

import dev.trakr.api.reporting.ReportViews.AssigneeSlice;
import dev.trakr.api.reporting.ReportViews.ReportIssue;
import dev.trakr.api.reporting.ReportViews.StatusSlice;
import dev.trakr.api.reporting.ReportViews.VelocityEntry;
import dev.trakr.api.reporting.internal.BurndownMath.Snapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Read-only queries over the report read models (sprint_day_snapshot, sprint_report — written by the workers)
 * and the issue tables. The reporting module owns no tables; like home, it only projects (01-architecture §4).
 */
@Component
public class ReportQueries {

    /** The sprint fields every report starts from. */
    public record SprintRow(UUID id, String name, String state, @Nullable LocalDate startsOn,
            @Nullable LocalDate endsOn, @Nullable BigDecimal committedPoints, List<UUID> committedIssueIds) {
    }

    /** What sprint_report stored when the sprint closed. */
    public record ClosedReport(BigDecimal committed, BigDecimal completed, BigDecimal added, BigDecimal removed,
            List<UUID> completedIds, List<UUID> incompleteIds) {
    }

    private final DSLContext dsl;

    ReportQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    // BLOCK 1: which sprint
    public Optional<SprintRow> sprint(UUID projectId, UUID sprintId) {
        return sprintWhere(SPRINT.PROJECT_ID.eq(projectId).and(SPRINT.ID.eq(sprintId)));
    }

    /** The sprint a report shows when none is named: the active one, else the one closed last. */
    public Optional<SprintRow> defaultSprint(UUID projectId) {
        return sprintWhere(SPRINT.PROJECT_ID.eq(projectId).and(SPRINT.STATE.eq("active")))
                .or(() -> dsl.select(SPRINT.ID).from(SPRINT)
                        .where(SPRINT.PROJECT_ID.eq(projectId), SPRINT.STATE.eq("closed"))
                        .orderBy(SPRINT.COMPLETED_AT.desc()).limit(1)
                        .fetchOptional(SPRINT.ID)
                        .flatMap(id -> sprint(projectId, id)));
    }

    public Optional<UUID> activeSprintId(UUID projectId) {
        return dsl.select(SPRINT.ID).from(SPRINT).where(SPRINT.PROJECT_ID.eq(projectId), SPRINT.STATE.eq("active"))
                .fetchOptional(SPRINT.ID);
    }

    private Optional<SprintRow> sprintWhere(Condition condition) {
        return dsl.select(SPRINT.ID, SPRINT.NAME, SPRINT.STATE, SPRINT.STARTS_ON, SPRINT.ENDS_ON,
                        SPRINT.COMMITTED_POINTS, SPRINT.COMMITTED_ISSUE_IDS.convertFrom(ReportQueries::list))
                .from(SPRINT).where(condition)
                .fetchOptional(Records.mapping(SprintRow::new));
    }

    // BLOCK 2: burndown — the day rows the report projector wrote, as a sorted map for floor lookups
    public NavigableMap<LocalDate, Snapshot> snapshots(UUID sprintId) {
        NavigableMap<LocalDate, Snapshot> snapshots = new TreeMap<>();
        dsl.select(SPRINT_DAY_SNAPSHOT.DAY, SPRINT_DAY_SNAPSHOT.SCOPE_POINTS, SPRINT_DAY_SNAPSHOT.REMAINING_POINTS)
                .from(SPRINT_DAY_SNAPSHOT)
                .where(SPRINT_DAY_SNAPSHOT.SPRINT_ID.eq(sprintId))
                .forEach(r -> snapshots.put(r.value1(), new Snapshot(r.value2(), r.value3())));
        return snapshots;
    }

    // BLOCK 3: velocity — the last N closed sprints, oldest first (a chart reads left to right in time)
    public List<VelocityEntry> velocity(UUID projectId, int last) {
        // a sprint closed while the workers were down has no report row yet: fall back to its own commitment
        List<VelocityEntry> newestFirst = dsl.select(SPRINT.ID, SPRINT.NAME,
                        DSL.coalesce(SPRINT_REPORT.COMMITTED_POINTS, DSL.coalesce(SPRINT.COMMITTED_POINTS, BigDecimal.ZERO)),
                        DSL.coalesce(SPRINT_REPORT.COMPLETED_POINTS, BigDecimal.ZERO))
                .from(SPRINT)
                .leftJoin(SPRINT_REPORT).on(SPRINT_REPORT.SPRINT_ID.eq(SPRINT.ID))
                .where(SPRINT.PROJECT_ID.eq(projectId), SPRINT.STATE.eq("closed"))
                .orderBy(SPRINT.COMPLETED_AT.desc())
                .limit(last)
                .fetch(r -> new VelocityEntry(r.value1(), r.value2(), r.value3(), r.value4()));
        return newestFirst.reversed();
    }

    // BLOCK 4: sprint report
    public Optional<ClosedReport> closedReport(UUID sprintId) {
        return dsl.select(SPRINT_REPORT.COMMITTED_POINTS, SPRINT_REPORT.COMPLETED_POINTS, SPRINT_REPORT.ADDED_POINTS,
                        SPRINT_REPORT.REMOVED_POINTS, SPRINT_REPORT.COMPLETED_ISSUE_IDS.convertFrom(ReportQueries::list),
                        SPRINT_REPORT.INCOMPLETE_ISSUE_IDS.convertFrom(ReportQueries::list))
                .from(SPRINT_REPORT).where(SPRINT_REPORT.SPRINT_ID.eq(sprintId))
                .fetchOptional(Records.mapping(ClosedReport::new));
    }

    /** A report row with its id, so the service can sort issues into completed / not completed / added. */
    public record IssueRow(UUID id, ReportIssue issue) {
    }

    public List<IssueRow> issuesInSprint(UUID sprintId) {
        return issues(ISSUE.SPRINT_ID.eq(sprintId));
    }

    public List<IssueRow> issuesWithIds(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : issues(ISSUE.ID.in(ids));
    }

    /** Today's points of the committed issues that are no longer in the sprint (or were deleted): "removed". */
    public BigDecimal pointsLeftSprint(Collection<UUID> committedIds, UUID sprintId) {
        if (committedIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return dsl.select(DSL.coalesce(DSL.sum(ISSUE.STORY_POINTS), BigDecimal.ZERO))
                .from(ISSUE)
                .where(ISSUE.ID.in(committedIds),
                        ISSUE.SPRINT_ID.isDistinctFrom(sprintId).or(ISSUE.DELETED_AT.isNotNull()))
                .fetchSingle()
                .value1();
    }

    // ordered by number, like keys read: MAR-24, MAR-25, …
    private List<IssueRow> issues(Condition condition) {
        return dsl.select(ISSUE.ID, ISSUE.KEY, ISSUE.SUMMARY, ISSUE.TYPE, ISSUE.STORY_POINTS, WORKFLOW_STATUS.NAME,
                        ISSUE.STATUS_CATEGORY)
                .from(ISSUE)
                .join(WORKFLOW_STATUS).on(WORKFLOW_STATUS.ID.eq(ISSUE.STATUS_ID))
                .where(condition, ISSUE.DELETED_AT.isNull())
                .orderBy(ISSUE.NUMBER)
                .fetch(r -> new IssueRow(r.value1(), new ReportIssue(r.value2(), r.value3(), r.value4(), r.value5(),
                        r.value6(), r.value7())));
    }

    // BLOCK 5: breakdown — one GROUP BY per dimension over the same set of issues
    public List<StatusSlice> byStatus(UUID projectId, @Nullable UUID sprintId) {
        Field<BigDecimal> points = DSL.coalesce(DSL.sum(ISSUE.STORY_POINTS), BigDecimal.ZERO);
        // a LEFT JOIN from the statuses: a status nobody is in still shows, with 0
        return dsl.select(WORKFLOW_STATUS.ID, WORKFLOW_STATUS.NAME, WORKFLOW_STATUS.CATEGORY, WORKFLOW_STATUS.COLOR,
                        DSL.count(ISSUE.ID), points)
                .from(WORKFLOW_STATUS)
                .leftJoin(ISSUE).on(ISSUE.STATUS_ID.eq(WORKFLOW_STATUS.ID), ISSUE.DELETED_AT.isNull(),
                        sprintId == null ? DSL.noCondition() : ISSUE.SPRINT_ID.eq(sprintId))
                .where(WORKFLOW_STATUS.PROJECT_ID.eq(projectId))
                .groupBy(WORKFLOW_STATUS.ID, WORKFLOW_STATUS.NAME, WORKFLOW_STATUS.CATEGORY, WORKFLOW_STATUS.COLOR,
                        WORKFLOW_STATUS.POSITION)
                .orderBy(WORKFLOW_STATUS.POSITION)
                .fetch(Records.mapping(StatusSlice::new));
    }

    /** Open work per person (done issues aren't load), most points first; unassigned last. */
    public List<AssigneeSlice> byAssignee(UUID projectId, @Nullable UUID sprintId) {
        Field<BigDecimal> points = DSL.coalesce(DSL.sum(ISSUE.STORY_POINTS), BigDecimal.ZERO);
        return dsl.select(APP_USER.ID, DSL.coalesce(APP_USER.DISPLAY_NAME, DSL.inline("Unassigned")),
                        APP_USER.INITIALS, APP_USER.AVATAR_COLOR, DSL.count(), points)
                .from(ISSUE)
                .leftJoin(APP_USER).on(APP_USER.ID.eq(ISSUE.ASSIGNEE_ID))
                .where(ISSUE.PROJECT_ID.eq(projectId), ISSUE.DELETED_AT.isNull(), ISSUE.STATUS_CATEGORY.ne("done"),
                        ISSUE.TYPE.ne("epic"), sprintId == null ? DSL.noCondition() : ISSUE.SPRINT_ID.eq(sprintId))
                .groupBy(APP_USER.ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS, APP_USER.AVATAR_COLOR)
                .orderBy(APP_USER.ID.isNull(), points.desc(), DSL.count().desc())
                .fetch(Records.mapping(AssigneeSlice::new));
    }

    private static List<UUID> list(UUID @Nullable [] ids) {
        return ids == null ? List.of() : List.of(ids);
    }
}

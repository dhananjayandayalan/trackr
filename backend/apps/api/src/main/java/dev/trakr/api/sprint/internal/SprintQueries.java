package dev.trakr.api.sprint.internal;

import static dev.trakr.jooq.Tables.ISSUE;
import static dev.trakr.jooq.Tables.PROJECT;
import static dev.trakr.jooq.Tables.SPRINT;
import static dev.trakr.jooq.Tables.WORKFLOW_STATUS;

import dev.trakr.api.shared.web.EpicColors;
import dev.trakr.api.sprint.BacklogFilter;
import dev.trakr.api.sprint.BacklogView;
import dev.trakr.api.sprint.BacklogView.BacklogRow;
import dev.trakr.api.sprint.BacklogView.EpicSummary;
import dev.trakr.api.sprint.BacklogView.IssueList;
import dev.trakr.api.sprint.BacklogView.Points;
import dev.trakr.api.sprint.BacklogView.SprintList;
import dev.trakr.api.sprint.SprintState;
import dev.trakr.api.sprint.SprintView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads for the sprint module: the backlog screen in one query, and the numbers a sprint's start and
 * report need. Reads issue, workflow_status and project — other modules' tables, read-only.
 */
@Component
public class SprintQueries {

    private final DSLContext dsl;

    SprintQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** One issue of a sprint, with what the commitment and the report need. */
    public record SprintIssue(UUID id, @Nullable BigDecimal storyPoints, String statusCategory) {

        public BigDecimal points() {
            return storyPoints == null ? BigDecimal.ZERO : storyPoints;
        }
    }

    public String templateOf(UUID projectId) {
        return dsl.select(PROJECT.TEMPLATE).from(PROJECT).where(PROJECT.ID.eq(projectId)).fetchSingle(PROJECT.TEMPLATE);
    }

    /** The live issues in a sprint, in rank order. */
    public List<SprintIssue> issuesOf(UUID sprintId) {
        return dsl.select(ISSUE.ID, ISSUE.STORY_POINTS, ISSUE.STATUS_CATEGORY)
                .from(ISSUE)
                .where(ISSUE.SPRINT_ID.eq(sprintId), ISSUE.DELETED_AT.isNull())
                .orderBy(ISSUE.RANK)
                .fetch(Records.mapping(SprintIssue::new));
    }

    /** Points of the committed issues that left the sprint (moved out, or deleted). */
    public BigDecimal pointsLeft(UUID sprintId, Collection<UUID> committedIds) {
        if (committedIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = dsl.select(DSL.sum(ISSUE.STORY_POINTS))
                .from(ISSUE)
                .where(ISSUE.ID.in(committedIds),
                        ISSUE.SPRINT_ID.isDistinctFrom(sprintId).or(ISSUE.DELETED_AT.isNotNull()))
                .fetchOne(0, BigDecimal.class);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    // BLOCK: the backlog — sprints with their issues nested, the backlog list and the epics, one round trip
    public BacklogView backlog(UUID projectId, BacklogFilter filter) {
        var row = ISSUE.as("row");
        var status = WORKFLOW_STATUS.as("row_status");
        var parent = ISSUE.as("row_parent");
        List<Condition> conditions = new ArrayList<>(List.of(row.PROJECT_ID.eq(projectId), row.DELETED_AT.isNull(),
                row.TYPE.notIn("epic", "subtask")));
        if (filter.epicId() != null) {
            conditions.add(row.PARENT_ID.eq(filter.epicId()));
        }
        if (filter.type() != null) {
            conditions.add(row.TYPE.eq(filter.type()));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            String q = filter.q().strip();
            conditions.add(row.SUMMARY.containsIgnoreCase(q).or(row.KEY.equalIgnoreCase(q)));
        }

        // stories, bugs and tasks have an epic as parent (Issue.java enforces it), so the parent is the epic.
        // A function, not one shared query: jOOQ builders are mutable, a second where() would change the first.
        Field<UUID> epicId = DSL.when(parent.TYPE.eq("epic"), parent.ID);
        Function<Condition, Field<List<BacklogRow>>> rowsOf = list -> DSL.multiset(
                        DSL.select(row.ID, row.KEY, row.SUMMARY, row.TYPE, row.PRIORITY, row.STATUS_ID, status.NAME,
                                        row.STATUS_CATEGORY, row.ASSIGNEE_ID, row.STORY_POINTS, epicId, row.RANK,
                                        row.FLAGGED, row.VERSION)
                                .from(row)
                                .join(status).on(status.ID.eq(row.STATUS_ID))
                                .leftJoin(parent).on(parent.ID.eq(row.PARENT_ID))
                                .where(conditions).and(list)
                                .orderBy(row.RANK))
                .convertFrom(rows -> rows.map(Records.mapping(BacklogRow::new)));

        Field<List<BacklogRow>> sprintRows = rowsOf.apply(row.SPRINT_ID.eq(SPRINT.ID)).as("issues");
        Field<List<SprintList>> sprints = DSL.multiset(
                        DSL.select(SPRINT.ID, SPRINT.PROJECT_ID, SPRINT.NAME, SPRINT.GOAL, SPRINT.STATE,
                                        SPRINT.STARTS_ON, SPRINT.ENDS_ON, SPRINT.POSITION, SPRINT.STARTED_AT,
                                        SPRINT.COMPLETED_AT, SPRINT.COMMITTED_POINTS, SPRINT.VERSION, sprintRows)
                                .from(SPRINT)
                                .where(SPRINT.PROJECT_ID.eq(projectId), SPRINT.STATE.in("planned", "active"))
                                // the active sprint first, then the planned ones in their order
                                .orderBy(DSL.when(SPRINT.STATE.eq("active"), 0).else_(1), SPRINT.POSITION,
                                        SPRINT.CREATED_AT))
                .as("sprints")
                .convertFrom(rows -> rows.map(r -> {
                    SprintView sprint = new SprintView(r.value1(), r.value2(), r.value3(), r.value4(),
                            SprintState.fromValue(r.value5()), r.value6(), r.value7(), r.value8(), instant(r.value9()),
                            instant(r.value10()), r.value11(), r.value12());
                    return new SprintList(sprint, r.value13(), Points.of(r.value13()));
                }));
        Field<List<BacklogRow>> backlogRows = rowsOf.apply(row.SPRINT_ID.isNull()).as("backlog");

        var epic = ISSUE.as("epic");
        var child = ISSUE.as("child");
        Field<Integer> children = DSL.field(DSL.selectCount().from(child)
                .where(child.PARENT_ID.eq(epic.ID), child.DELETED_AT.isNull()));
        Field<Integer> doneChildren = DSL.field(DSL.selectCount().from(child)
                .where(child.PARENT_ID.eq(epic.ID), child.DELETED_AT.isNull(), child.STATUS_CATEGORY.eq("done")));
        Field<List<EpicSummary>> epics = DSL.multiset(
                        DSL.select(epic.ID, epic.KEY, epic.SUMMARY, epic.NUMBER, children, doneChildren)
                                .from(epic)
                                .where(epic.PROJECT_ID.eq(projectId), epic.TYPE.eq("epic"), epic.DELETED_AT.isNull())
                                .orderBy(epic.RANK))
                .as("epics")
                .convertFrom(rows -> rows.map(r -> new EpicSummary(r.value1(), r.value2(), r.value3(),
                        EpicColors.of(r.value4()), r.value5(), r.value6(),
                        r.value5() == 0 ? 0 : r.value6() * 100 / r.value5())));

        var result = dsl.select(sprints, backlogRows, epics).fetchSingle();
        SprintList active = result.value1().stream()
                .filter(list -> list.sprint().state() == SprintState.ACTIVE)
                .findFirst()
                .orElse(null);
        List<SprintList> planned = result.value1().stream()
                .filter(list -> list.sprint().state() == SprintState.PLANNED)
                .toList();
        return new BacklogView(active, planned, new IssueList(result.value2(), Points.of(result.value2())),
                result.value3());
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** Whether the sprint is a planned sprint of this project (a valid target when completing another). */
    public boolean isPlannedSprintOf(UUID projectId, UUID sprintId) {
        return dsl.fetchExists(SPRINT, SPRINT.ID.eq(sprintId), SPRINT.PROJECT_ID.eq(projectId),
                SPRINT.STATE.eq("planned"));
    }
}

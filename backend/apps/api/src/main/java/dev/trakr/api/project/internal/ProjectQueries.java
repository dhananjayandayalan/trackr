package dev.trakr.api.project.internal;

import static dev.trakr.jooq.Tables.APP_USER;
import static dev.trakr.jooq.Tables.BOARD;
import static dev.trakr.jooq.Tables.BOARD_COLUMN;
import static dev.trakr.jooq.Tables.ISSUE;
import static dev.trakr.jooq.Tables.PROJECT;
import static dev.trakr.jooq.Tables.PROJECT_MEMBER;
import static dev.trakr.jooq.Tables.PROJECT_STAR;
import static dev.trakr.jooq.Tables.PROJECT_VISIT;
import static dev.trakr.jooq.Tables.SPRINT;
import static dev.trakr.jooq.Tables.WORKFLOW_STATUS;
import static dev.trakr.jooq.Tables.WORKFLOW_TRANSITION;

import dev.trakr.api.project.Access;
import dev.trakr.api.project.MemberView;
import dev.trakr.api.project.ProjectDetails;
import dev.trakr.api.project.ProjectFilter;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.project.ProjectSummary;
import dev.trakr.api.project.Template;
import dev.trakr.api.project.TimelineView;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads for project screens, straight into records (no entities). The detail reads also cover
 * workflow, board and user tables: read-only projections across modules are what jOOQ is for here
 * (like the home module, 01-architecture §4); writes to those tables stay in their own modules.
 */
@Component
public class ProjectQueries {

    private final DSLContext dsl;

    ProjectQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<ProjectSummary> listVisible(UUID userId, List<UUID> workspaceIds, ProjectFilter filter) {
        // BLOCK 1: the fields; "starred" is a correlated EXISTS, so it costs no join and no duplicate rows
        Field<Boolean> starred = DSL.field(DSL.exists(DSL.selectOne().from(PROJECT_STAR)
                .where(PROJECT_STAR.PROJECT_ID.eq(PROJECT.ID), PROJECT_STAR.USER_ID.eq(userId))));

        List<Condition> conditions = new ArrayList<>();
        conditions.add(PROJECT.WORKSPACE_ID.in(workspaceIds));
        conditions.add(PROJECT.ARCHIVED_AT.isNull());
        // BLOCK 2: visibility — a member, or a limited/open project (the caller is in each of these workspaces)
        conditions.add(PROJECT.ACCESS.in(Access.LIMITED.value(), Access.OPEN.value())
                .or(DSL.exists(DSL.selectOne().from(PROJECT_MEMBER)
                        .where(PROJECT_MEMBER.PROJECT_ID.eq(PROJECT.ID), PROJECT_MEMBER.USER_ID.eq(userId)))));
        // BLOCK 3: optional filters, one condition each, ANDed together
        if (filter.q() != null && !filter.q().isBlank()) {
            conditions.add(PROJECT.NAME.containsIgnoreCase(filter.q().strip())
                    .or(PROJECT.KEY.containsIgnoreCase(filter.q().strip())));
        }
        if (filter.template() != null) {
            conditions.add(PROJECT.TEMPLATE.eq(filter.template().value()));
        }
        if (filter.leadId() != null) {
            conditions.add(PROJECT.LEAD_ID.eq(filter.leadId()));
        }
        if (filter.starred()) {
            conditions.add(starred.isTrue());
        }

        // BLOCK 4: the card's numbers. Issue counts in one grouped derived table (one pass over the project's
        // issues, using count(*) FILTER), the sprint and the visit as correlated subqueries: at most one row each.
        var counts = DSL.select(ISSUE.PROJECT_ID,
                        DSL.count().filterWhere(ISSUE.STATUS_CATEGORY.ne("done")).as("open_issues"),
                        DSL.count().filterWhere(ISSUE.STATUS_CATEGORY.eq("done")).as("done_issues"),
                        DSL.count().as("all_issues"))
                .from(ISSUE)
                .where(ISSUE.DELETED_AT.isNull())
                .groupBy(ISSUE.PROJECT_ID)
                .asTable("counts");
        Field<Integer> open = DSL.coalesce(counts.field("open_issues", Integer.class), 0);
        Field<Integer> done = DSL.coalesce(counts.field("done_issues", Integer.class), 0);
        Field<Integer> all = counts.field("all_issues", Integer.class);
        // integer percent; NULLIF turns "no issues" into NULL instead of a division by zero, coalesce makes it 0
        Field<Integer> progress = DSL.coalesce(
                DSL.field("({0} * 100 / NULLIF({1}, 0))", Integer.class, done, all), 0);
        Field<String> activeSprint = DSL.field(DSL.select(SPRINT.NAME).from(SPRINT)
                .where(SPRINT.PROJECT_ID.eq(PROJECT.ID), SPRINT.STATE.eq("active")).limit(1));
        Field<OffsetDateTime> visited = DSL.field(DSL.select(PROJECT_VISIT.VISITED_AT).from(PROJECT_VISIT)
                .where(PROJECT_VISIT.PROJECT_ID.eq(PROJECT.ID), PROJECT_VISIT.USER_ID.eq(userId)));

        // BLOCK 5: one query, each row mapped onto the record
        return dsl.select(PROJECT.ID, PROJECT.KEY, PROJECT.NAME, PROJECT.TEMPLATE, PROJECT.ACCESS, PROJECT.COLOR,
                        APP_USER.ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS, APP_USER.AVATAR_COLOR, starred, open,
                        progress, activeSprint, visited)
                .from(PROJECT)
                .join(APP_USER).on(APP_USER.ID.eq(PROJECT.LEAD_ID))
                .leftJoin(counts).on(counts.field(ISSUE.PROJECT_ID).eq(PROJECT.ID))
                .where(DSL.and(conditions))
                .orderBy(PROJECT.NAME)
                .fetch(r -> new ProjectSummary(r.value1(), r.value2(), r.value3(), Template.fromValue(r.value4()),
                        Access.fromValue(r.value5()), r.value6(),
                        new ProjectSummary.Lead(r.value7(), r.value8(), r.value9(), r.value10()), r.value11(),
                        r.value12(), r.value13(), r.value14(),
                        r.value15() == null ? null : r.value15().toInstant()));
    }

    public List<ProjectDetails.Status> statusesOf(UUID projectId) {
        return dsl.select(WORKFLOW_STATUS.ID, WORKFLOW_STATUS.NAME, WORKFLOW_STATUS.CATEGORY, WORKFLOW_STATUS.POSITION,
                        WORKFLOW_STATUS.COLOR, WORKFLOW_STATUS.WIP_LIMIT)
                .from(WORKFLOW_STATUS)
                .where(WORKFLOW_STATUS.PROJECT_ID.eq(projectId))
                .orderBy(WORKFLOW_STATUS.POSITION)
                .fetch(Records.mapping(ProjectDetails.Status::new));
    }

    public List<ProjectDetails.Transition> transitionsOf(UUID projectId) {
        return dsl.select(WORKFLOW_TRANSITION.ID, WORKFLOW_TRANSITION.FROM_STATUS_ID, WORKFLOW_TRANSITION.TO_STATUS_ID)
                .from(WORKFLOW_TRANSITION)
                .where(WORKFLOW_TRANSITION.PROJECT_ID.eq(projectId))
                .fetch(Records.mapping(ProjectDetails.Transition::new));
    }

    // the board with its columns nested: MULTISET is one SQL query that returns the columns as a nested list
    public Optional<ProjectDetails.Board> boardOf(UUID projectId) {
        return dsl.select(BOARD.ID, BOARD.NAME, BOARD.TYPE,
                        DSL.multiset(DSL.select(BOARD_COLUMN.ID, BOARD_COLUMN.NAME, BOARD_COLUMN.POSITION,
                                                BOARD_COLUMN.STATUS_IDS.convertFrom(List::of), BOARD_COLUMN.WIP_LIMIT)
                                        .from(BOARD_COLUMN)
                                        .where(BOARD_COLUMN.BOARD_ID.eq(BOARD.ID))
                                        .orderBy(BOARD_COLUMN.POSITION))
                                .convertFrom(columns -> columns.map(Records.mapping(ProjectDetails.Column::new))))
                .from(BOARD)
                .where(BOARD.PROJECT_ID.eq(projectId))
                .fetchOptional(Records.mapping(ProjectDetails.Board::new));
    }

    public List<MemberView> membersOf(UUID projectId) {
        return dsl.select(APP_USER.ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS, APP_USER.AVATAR_COLOR,
                        PROJECT_MEMBER.ROLE.convertFrom(ProjectRole::fromValue))
                .from(PROJECT_MEMBER)
                .join(APP_USER).on(APP_USER.ID.eq(PROJECT_MEMBER.USER_ID))
                .where(PROJECT_MEMBER.PROJECT_ID.eq(projectId))
                .orderBy(APP_USER.DISPLAY_NAME)
                .fetch(Records.mapping(MemberView::new));
    }

    /**
     * The timeline (phase 17): every epic with its children nested (MULTISET, one query) and its progress counted
     * with {@code count(*) FILTER (WHERE …)} — one pass over the children for both numbers. With {@code from}/
     * {@code to}, only epics whose dates touch that window, plus the undated ones (they still need a place).
     */
    public List<TimelineView.Epic> timeline(UUID projectId, @Nullable LocalDate from, @Nullable LocalDate to) {
        var child = ISSUE.as("child");
        Field<Integer> childCount = DSL.field(DSL.selectCount().from(child)
                .where(child.PARENT_ID.eq(ISSUE.ID), child.DELETED_AT.isNull()));
        Field<Integer> doneCount = DSL.field(DSL.select(DSL.count().filterWhere(child.STATUS_CATEGORY.eq("done")))
                .from(child).where(child.PARENT_ID.eq(ISSUE.ID), child.DELETED_AT.isNull()));

        List<Condition> conditions = new ArrayList<>();
        conditions.add(ISSUE.PROJECT_ID.eq(projectId));
        conditions.add(ISSUE.TYPE.eq("epic"));
        conditions.add(ISSUE.DELETED_AT.isNull());
        if (from != null) {
            conditions.add(DSL.coalesce(ISSUE.DUE_DATE, ISSUE.START_DATE).isNull()
                    .or(DSL.coalesce(ISSUE.DUE_DATE, ISSUE.START_DATE).ge(from)));
        }
        if (to != null) {
            conditions.add(DSL.coalesce(ISSUE.START_DATE, ISSUE.DUE_DATE).isNull()
                    .or(DSL.coalesce(ISSUE.START_DATE, ISSUE.DUE_DATE).le(to)));
        }

        return dsl.select(ISSUE.ID, ISSUE.KEY, ISSUE.SUMMARY, ISSUE.STATUS_CATEGORY, ISSUE.START_DATE, ISSUE.DUE_DATE,
                        childCount, doneCount, ISSUE.VERSION,
                        DSL.multiset(DSL.select(child.ID, child.KEY, child.SUMMARY, child.TYPE, child.STATUS_CATEGORY,
                                                child.START_DATE, child.DUE_DATE, child.ASSIGNEE_ID, child.VERSION)
                                        .from(child)
                                        .where(child.PARENT_ID.eq(ISSUE.ID), child.DELETED_AT.isNull())
                                        .orderBy(child.START_DATE.nullsLast(), child.DUE_DATE.nullsLast(), child.NUMBER))
                                .convertFrom(rows -> rows.map(Records.mapping(TimelineView.Child::new))))
                .from(ISSUE)
                .where(conditions)
                // dated epics in date order, then the undated ones in key order
                .orderBy(DSL.coalesce(ISSUE.START_DATE, ISSUE.DUE_DATE).nullsLast(), ISSUE.NUMBER)
                .fetch(r -> new TimelineView.Epic(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(),
                        r.value6(), r.value7(), r.value8(),
                        r.value7() == 0 ? 0 : r.value8() * 100 / r.value7(), r.value9(), r.value10()));
    }
}

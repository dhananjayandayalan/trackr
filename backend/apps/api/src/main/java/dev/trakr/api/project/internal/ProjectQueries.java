package dev.trakr.api.project.internal;

import static dev.trakr.jooq.Tables.APP_USER;
import static dev.trakr.jooq.Tables.BOARD;
import static dev.trakr.jooq.Tables.BOARD_COLUMN;
import static dev.trakr.jooq.Tables.PROJECT;
import static dev.trakr.jooq.Tables.PROJECT_MEMBER;
import static dev.trakr.jooq.Tables.PROJECT_STAR;
import static dev.trakr.jooq.Tables.WORKFLOW_STATUS;
import static dev.trakr.jooq.Tables.WORKFLOW_TRANSITION;

import dev.trakr.api.project.Access;
import dev.trakr.api.project.MemberView;
import dev.trakr.api.project.ProjectDetails;
import dev.trakr.api.project.ProjectFilter;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.project.ProjectSummary;
import dev.trakr.api.project.Template;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Records;
import org.jooq.impl.DSL;
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

        // BLOCK 4: one query, mapped by position onto the record's constructor
        return dsl.select(PROJECT.ID, PROJECT.KEY, PROJECT.NAME,
                        PROJECT.TEMPLATE.convertFrom(Template::fromValue),
                        PROJECT.ACCESS.convertFrom(Access::fromValue),
                        PROJECT.COLOR, PROJECT.LEAD_ID, starred)
                .from(PROJECT)
                .where(DSL.and(conditions))
                .orderBy(PROJECT.NAME)
                .fetch(Records.mapping(ProjectSummary::new));
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
}

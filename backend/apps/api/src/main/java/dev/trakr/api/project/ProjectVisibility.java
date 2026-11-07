package dev.trakr.api.project;

import static dev.trakr.jooq.Tables.PROJECT;
import static dev.trakr.jooq.Tables.PROJECT_MEMBER;
import static dev.trakr.jooq.Tables.WORKSPACE_MEMBER;

import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * ProjectAccess's "may this user see the project?" rule as a SQL condition, for list queries in other
 * modules (links to other issues, "Your work", activity) that filter many rows at once and can't call
 * ProjectAccess per row. Kept here, next to ProjectAccess, so the two can't drift apart.
 */
public final class ProjectVisibility {

    private ProjectVisibility() {
    }

    /** True when {@code viewerId} is a member of the project, or it is limited/open and they're in its workspace. */
    public static Condition visibleTo(Field<UUID> projectId, UUID viewerId) {
        var p = PROJECT.as("visible_project");
        return DSL.exists(DSL.selectOne().from(p).where(p.ID.eq(projectId))
                .and(DSL.exists(DSL.selectOne().from(PROJECT_MEMBER)
                                .where(PROJECT_MEMBER.PROJECT_ID.eq(p.ID), PROJECT_MEMBER.USER_ID.eq(viewerId)))
                        .or(p.ACCESS.in(Access.LIMITED.value(), Access.OPEN.value())
                                .and(DSL.exists(DSL.selectOne().from(WORKSPACE_MEMBER)
                                        .where(WORKSPACE_MEMBER.WORKSPACE_ID.eq(p.WORKSPACE_ID),
                                                WORKSPACE_MEMBER.USER_ID.eq(viewerId)))))));
    }
}

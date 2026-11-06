package dev.trakr.api.workspace.internal;

import static dev.trakr.jooq.Tables.WORKSPACE;
import static dev.trakr.jooq.Tables.WORKSPACE_MEMBER;

import dev.trakr.api.workspace.CurrentWorkspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.springframework.stereotype.Component;

/** Reads that join tables use jOOQ and return records straight away, no entities. */
@Component
public class WorkspaceQueries {

    private final DSLContext dsl;

    WorkspaceQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    // MVP: one workspace per user, so "current" is the first one they joined
    public Optional<CurrentWorkspace> firstWorkspaceOf(UUID userId) {
        return dsl.select(WORKSPACE.ID, WORKSPACE.NAME, WORKSPACE.SLUG, WORKSPACE_MEMBER.ROLE)
                .from(WORKSPACE_MEMBER)
                .join(WORKSPACE).on(WORKSPACE.ID.eq(WORKSPACE_MEMBER.WORKSPACE_ID))
                .where(WORKSPACE_MEMBER.USER_ID.eq(userId))
                .orderBy(WORKSPACE_MEMBER.JOINED_AT)
                .limit(1)
                .fetchOptional(Records.mapping(CurrentWorkspace::new));
    }

    // first joined first: that is the user's own (current) workspace
    public List<UUID> workspaceIdsOf(UUID userId) {
        return dsl.select(WORKSPACE_MEMBER.WORKSPACE_ID)
                .from(WORKSPACE_MEMBER)
                .where(WORKSPACE_MEMBER.USER_ID.eq(userId))
                .orderBy(WORKSPACE_MEMBER.JOINED_AT)
                .fetch(WORKSPACE_MEMBER.WORKSPACE_ID);
    }
}

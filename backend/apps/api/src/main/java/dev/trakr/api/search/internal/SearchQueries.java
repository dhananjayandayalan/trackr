package dev.trakr.api.search.internal;

import static dev.trakr.jooq.Tables.APP_USER;
import static dev.trakr.jooq.Tables.ISSUE;
import static dev.trakr.jooq.Tables.PROJECT;
import static dev.trakr.jooq.Tables.PROJECT_MEMBER;
import static dev.trakr.jooq.Tables.SAVED_FILTER;
import static dev.trakr.jooq.Tables.SPRINT;
import static dev.trakr.jooq.Tables.WORKFLOW_STATUS;
import static dev.trakr.jooq.Tables.WORKSPACE_MEMBER;

import dev.trakr.api.issue.IssueDetails.UserRef;
import dev.trakr.api.project.ProjectVisibility;
import dev.trakr.api.search.SearchViews.FilterView;
import dev.trakr.api.search.SearchViews.ProjectSuggestion;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * The Postgres side of search: who and what the caller may see, and name → id lookups for TQL values.
 * Every lookup takes the visible project ids (or the caller's workspaces), so a value can't reach into
 * projects the caller can't see — {@code assignee = "Someone"} finds nobody outside your workspaces.
 */
@Component
public class SearchQueries {

    private static final char ESCAPE = '!';

    private final DSLContext dsl;

    SearchQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    // BLOCK 1: the security scope — ProjectVisibility is the same rule ProjectAccess applies to one project
    public List<UUID> visibleProjectIds(UUID userId) {
        return dsl.select(PROJECT.ID).from(PROJECT)
                .where(ProjectVisibility.visibleTo(PROJECT.ID, userId))
                .fetch(PROJECT.ID);
    }

    public List<ProjectSuggestion> projects(Collection<UUID> projectIds, String query, int limit) {
        String like = "%" + escapeLike(query) + "%";
        return dsl.select(PROJECT.KEY, PROJECT.NAME, PROJECT.COLOR).from(PROJECT)
                .where(PROJECT.ID.in(projectIds), PROJECT.ARCHIVED_AT.isNull(),
                        PROJECT.KEY.likeIgnoreCase(escapeLike(query) + "%", ESCAPE)
                                .or(PROJECT.NAME.likeIgnoreCase(like, ESCAPE)))
                // an exact key first, then by name
                .orderBy(DSL.when(PROJECT.KEY.equalIgnoreCase(query), 0).otherwise(1), PROJECT.NAME)
                .limit(limit)
                .fetch(r -> new ProjectSuggestion(r.value1(), r.value2(), r.value3()));
    }

    public List<String> projectKeys(Collection<UUID> projectIds) {
        return dsl.select(PROJECT.KEY).from(PROJECT).where(PROJECT.ID.in(projectIds)).orderBy(PROJECT.KEY)
                .fetch(PROJECT.KEY);
    }

    // BLOCK 2: people — members of the caller's workspaces only
    public List<UUID> usersNamed(Collection<UUID> workspaceIds, String nameOrEmail) {
        // email is citext, but a bound text parameter would make Postgres compare as text: lower() both sides
        return dsl.selectDistinct(APP_USER.ID).from(APP_USER)
                .where(inWorkspaces(workspaceIds), DSL.lower(APP_USER.DISPLAY_NAME).eq(lower(nameOrEmail))
                        .or(DSL.lower(APP_USER.EMAIL).eq(lower(nameOrEmail))))
                .fetch(APP_USER.ID);
    }

    /** Workspace members whose name starts with {@code prefix} (or a later word does): "gr" finds Grace Hopper. */
    public List<UserRef> people(Collection<UUID> workspaceIds, String prefix, int limit) {
        String start = escapeLike(prefix) + "%";
        return dsl.selectDistinct(APP_USER.ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS, APP_USER.AVATAR_COLOR)
                .from(APP_USER)
                .where(inWorkspaces(workspaceIds), APP_USER.DISPLAY_NAME.likeIgnoreCase(start, ESCAPE)
                        .or(APP_USER.DISPLAY_NAME.likeIgnoreCase("% " + start, ESCAPE)))
                .orderBy(APP_USER.DISPLAY_NAME)
                .limit(limit)
                .fetch(r -> new UserRef(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    public List<UUID> membersOf(Collection<UUID> projectIds, String projectKey) {
        return dsl.select(PROJECT_MEMBER.USER_ID).from(PROJECT_MEMBER)
                .join(PROJECT).on(PROJECT.ID.eq(PROJECT_MEMBER.PROJECT_ID))
                .where(PROJECT.ID.in(projectIds), PROJECT.KEY.eq(projectKey))
                .fetch(PROJECT_MEMBER.USER_ID);
    }

    // BLOCK 3: sprints, issues, statuses — inside the visible projects
    public List<UUID> sprintsNamed(Collection<UUID> projectIds, String name) {
        return dsl.select(SPRINT.ID).from(SPRINT)
                .where(SPRINT.PROJECT_ID.in(projectIds), DSL.lower(SPRINT.NAME).eq(lower(name)))
                .fetch(SPRINT.ID);
    }

    public List<UUID> sprintsInStates(Collection<UUID> projectIds, Collection<String> states) {
        return dsl.select(SPRINT.ID).from(SPRINT)
                .where(SPRINT.PROJECT_ID.in(projectIds), SPRINT.STATE.in(states))
                .fetch(SPRINT.ID);
    }

    /** Active first, then planned in backlog order, then the latest closed ones. */
    public List<String> sprintNames(Collection<UUID> projectIds, int limit) {
        return dsl.select(SPRINT.NAME).from(SPRINT)
                .where(SPRINT.PROJECT_ID.in(projectIds))
                .orderBy(DSL.when(SPRINT.STATE.eq("active"), 0).when(SPRINT.STATE.eq("planned"), 1).otherwise(2),
                        SPRINT.POSITION.desc())
                .limit(limit)
                .fetch(SPRINT.NAME);
    }

    public List<UUID> issuesWithKey(Collection<UUID> projectIds, String key) {
        return dsl.select(ISSUE.ID).from(ISSUE)
                .where(ISSUE.PROJECT_ID.in(projectIds), ISSUE.KEY.eq(key), ISSUE.DELETED_AT.isNull())
                .fetch(ISSUE.ID);
    }

    public List<String> epicKeys(Collection<UUID> projectIds, int limit) {
        return dsl.select(ISSUE.KEY).from(ISSUE)
                .where(ISSUE.PROJECT_ID.in(projectIds), ISSUE.TYPE.eq("epic"), ISSUE.DELETED_AT.isNull())
                .orderBy(ISSUE.UPDATED_AT.desc())
                .limit(limit)
                .fetch(ISSUE.KEY);
    }

    /** How the projects spell a status name: "done" → ["Done"]; two spellings in two projects → both. */
    public List<String> statusNames(Collection<UUID> projectIds, String name) {
        return dsl.selectDistinct(WORKFLOW_STATUS.NAME).from(WORKFLOW_STATUS)
                .where(WORKFLOW_STATUS.PROJECT_ID.in(projectIds), DSL.lower(WORKFLOW_STATUS.NAME).eq(lower(name)))
                .fetch(WORKFLOW_STATUS.NAME);
    }

    /** Every status name, roughly in workflow order. */
    public List<String> statusNames(Collection<UUID> projectIds) {
        return dsl.select(WORKFLOW_STATUS.NAME).from(WORKFLOW_STATUS)
                .where(WORKFLOW_STATUS.PROJECT_ID.in(projectIds))
                .groupBy(WORKFLOW_STATUS.NAME)
                .orderBy(DSL.min(WORKFLOW_STATUS.POSITION), WORKFLOW_STATUS.NAME)
                .fetch(WORKFLOW_STATUS.NAME);
    }

    // BLOCK 4: saved filters — mine, plus the ones others share in my workspaces; starred first
    public List<FilterView> filters(UUID userId, Collection<UUID> workspaceIds) {
        return filters(userId, workspaceIds, DSL.noCondition());
    }

    /** One filter, if the caller may see it. */
    public Optional<FilterView> filter(UUID userId, Collection<UUID> workspaceIds, UUID id) {
        return filters(userId, workspaceIds, SAVED_FILTER.ID.eq(id)).stream().findFirst();
    }

    private List<FilterView> filters(UUID userId, Collection<UUID> workspaceIds, Condition only) {
        return dsl.select(SAVED_FILTER.ID, SAVED_FILTER.NAME, SAVED_FILTER.TQL, SAVED_FILTER.STARRED,
                        SAVED_FILTER.SHARED, SAVED_FILTER.OWNER_ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS,
                        APP_USER.AVATAR_COLOR, SAVED_FILTER.CREATED_AT)
                .from(SAVED_FILTER)
                .join(APP_USER).on(APP_USER.ID.eq(SAVED_FILTER.OWNER_ID))
                .where(SAVED_FILTER.OWNER_ID.eq(userId)
                        .or(SAVED_FILTER.SHARED.isTrue().and(SAVED_FILTER.WORKSPACE_ID.in(workspaceIds))), only)
                .orderBy(SAVED_FILTER.STARRED.desc(), SAVED_FILTER.NAME)
                .fetch(r -> new FilterView(r.value1(), r.value2(), r.value3(),
                        // a shared filter shows as starred only to its owner, who is the one who starred it
                        r.value4() && r.value6().equals(userId), r.value5(), r.value6().equals(userId),
                        new UserRef(r.value6(), r.value7(), r.value8(), r.value9()), r.value10().toInstant()));
    }

    private static Condition inWorkspaces(Collection<UUID> workspaceIds) {
        return DSL.exists(DSL.selectOne().from(WORKSPACE_MEMBER)
                .where(WORKSPACE_MEMBER.USER_ID.eq(APP_USER.ID), WORKSPACE_MEMBER.WORKSPACE_ID.in(workspaceIds)));
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    // % and _ typed by the user are literal characters, not wildcards: each gets the ESCAPE character in front
    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}

package dev.trakr.api.issue.internal;

import static dev.trakr.jooq.Tables.APP_USER;
import static dev.trakr.jooq.Tables.ATTACHMENT;
import static dev.trakr.jooq.Tables.ISSUE;
import static dev.trakr.jooq.Tables.ISSUE_EVENT;
import static dev.trakr.jooq.Tables.ISSUE_LINK;
import static dev.trakr.jooq.Tables.ISSUE_STAR;
import static dev.trakr.jooq.Tables.SPRINT;
import static dev.trakr.jooq.Tables.WATCHER;
import static dev.trakr.jooq.Tables.WORKFLOW_STATUS;
import static dev.trakr.jooq.Tables.WORKLOG;

import dev.trakr.api.issue.HistoryItem;
import dev.trakr.api.issue.IssueDetails;
import dev.trakr.api.issue.IssueDetails.AttachmentView;
import dev.trakr.api.issue.IssueDetails.IssueRef;
import dev.trakr.api.issue.IssueDetails.LinkView;
import dev.trakr.api.issue.IssueDetails.SprintRef;
import dev.trakr.api.issue.IssueDetails.StatusRef;
import dev.trakr.api.issue.IssueDetails.TimeTracking;
import dev.trakr.api.issue.IssueDetails.UserRef;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.LinkType;
import dev.trakr.api.issue.Priority;
import dev.trakr.api.issue.WorklogView;
import dev.trakr.api.project.ProjectVisibility;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSON;
import org.jooq.Record8;
import org.jooq.Records;
import org.jooq.SelectField;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads for issue screens, straight into records (no entities). Like ProjectQueries, these also read
 * other modules' tables (workflow_status, app_user, sprint, project) — read-only projections; the
 * writes to those tables stay in their own modules.
 */
@Component
public class IssueQueries {

    private final DSLContext dsl;
    private final JsonMapper json;

    IssueQueries(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    /**
     * The whole issue page in one round trip. The subtasks and links are MULTISET fields: nested
     * SELECTs that Postgres returns as one JSON value per row, which jOOQ turns back into lists. The
     * alternative — one query per list — is the N+1 problem in miniature.
     */
    public Optional<IssueDetails> detail(UUID workspaceId, String key, UUID viewerId) {
        // BLOCK 1: aliases — the same table joined in different roles needs different names
        var parent = ISSUE.as("parent");
        var parentStatus = WORKFLOW_STATUS.as("parent_status");
        var assignee = APP_USER.as("assignee");
        var reporter = APP_USER.as("reporter");

        // BLOCK 2: nested lists
        Field<List<IssueRef>> subtasks = subtasksOf(ISSUE.ID);
        Field<List<LinkView>> links = linksOf(ISSUE.ID, viewerId);
        Field<List<AttachmentView>> attachments = attachmentsOf(ISSUE.ID);
        Field<Boolean> watching = DSL.field(DSL.exists(DSL.selectOne().from(WATCHER)
                .where(WATCHER.ISSUE_ID.eq(ISSUE.ID), WATCHER.USER_ID.eq(viewerId))));
        Field<Integer> watcherCount = DSL.field(DSL.selectCount().from(WATCHER).where(WATCHER.ISSUE_ID.eq(ISSUE.ID)));
        Field<Boolean> starred = DSL.field(DSL.exists(DSL.selectOne().from(ISSUE_STAR)
                .where(ISSUE_STAR.ISSUE_ID.eq(ISSUE.ID), ISSUE_STAR.USER_ID.eq(viewerId))));

        // BLOCK 3: the row
        List<SelectField<?>> fields = new ArrayList<>(List.of(ISSUE.fields()));
        fields.addAll(List.of(WORKFLOW_STATUS.NAME, WORKFLOW_STATUS.COLOR,
                reporter.DISPLAY_NAME, reporter.INITIALS, reporter.AVATAR_COLOR,
                assignee.DISPLAY_NAME, assignee.INITIALS, assignee.AVATAR_COLOR,
                parent.ID, parent.KEY, parent.SUMMARY, parent.TYPE, parent.STATUS_CATEGORY, parentStatus.NAME,
                SPRINT.NAME, SPRINT.STATE,
                subtasks, links, watching, watcherCount, starred, attachments));

        return dsl.select(fields)
                .from(ISSUE)
                .join(WORKFLOW_STATUS).on(WORKFLOW_STATUS.ID.eq(ISSUE.STATUS_ID))
                .join(reporter).on(reporter.ID.eq(ISSUE.REPORTER_ID))
                .leftJoin(assignee).on(assignee.ID.eq(ISSUE.ASSIGNEE_ID))
                // a deleted epic's children show no parent
                .leftJoin(parent).on(parent.ID.eq(ISSUE.PARENT_ID).and(parent.DELETED_AT.isNull()))
                .leftJoin(parentStatus).on(parentStatus.ID.eq(parent.STATUS_ID))
                .leftJoin(SPRINT).on(SPRINT.ID.eq(ISSUE.SPRINT_ID))
                .where(ISSUE.WORKSPACE_ID.eq(workspaceId), ISSUE.KEY.eq(key), ISSUE.DELETED_AT.isNull())
                // BLOCK 4: map by field — more than 22 columns, past what Records.mapping's typed overloads cover
                .fetchOptional(r -> new IssueDetails(
                        r.get(ISSUE.ID),
                        r.get(ISSUE.KEY),
                        projectKeyOf(r.get(ISSUE.KEY)),
                        IssueType.fromValue(r.get(ISSUE.TYPE)),
                        r.get(ISSUE.SUMMARY),
                        r.get(ISSUE.DESCRIPTION_HTML),
                        r.get(ISSUE.DESCRIPTION_TEXT),
                        new StatusRef(r.get(ISSUE.STATUS_ID), r.get(WORKFLOW_STATUS.NAME), r.get(ISSUE.STATUS_CATEGORY),
                                r.get(WORKFLOW_STATUS.COLOR)),
                        Priority.fromValue(r.get(ISSUE.PRIORITY)),
                        r.get(ISSUE.ASSIGNEE_ID) == null ? null : new UserRef(r.get(ISSUE.ASSIGNEE_ID),
                                r.get(assignee.DISPLAY_NAME), r.get(assignee.INITIALS), r.get(assignee.AVATAR_COLOR)),
                        new UserRef(r.get(ISSUE.REPORTER_ID), r.get(reporter.DISPLAY_NAME), r.get(reporter.INITIALS),
                                r.get(reporter.AVATAR_COLOR)),
                        r.get(parent.ID) == null ? null : new IssueRef(r.get(parent.ID), r.get(parent.KEY),
                                r.get(parent.SUMMARY), IssueType.fromValue(r.get(parent.TYPE)),
                                r.get(parent.STATUS_CATEGORY), r.get(parentStatus.NAME)),
                        r.get(ISSUE.SPRINT_ID) == null ? null
                                : new SprintRef(r.get(ISSUE.SPRINT_ID), r.get(SPRINT.NAME), r.get(SPRINT.STATE)),
                        r.get(ISSUE.STORY_POINTS),
                        r.get(ISSUE.RANK),
                        List.of(r.get(ISSUE.LABELS)),
                        r.get(ISSUE.DUE_DATE),
                        r.get(ISSUE.START_DATE),
                        r.get(ISSUE.FLAGGED),
                        r.get(ISSUE.FIX_VERSION),
                        TimeTracking.of(r.get(ISSUE.ORIGINAL_ESTIMATE_MIN), r.get(ISSUE.TIME_SPENT_MIN)),
                        r.get(subtasks),
                        r.get(links),
                        r.get(watching),
                        r.get(watcherCount),
                        r.get(starred),
                        r.get(attachments),
                        instant(r.get(ISSUE.RESOLVED_AT)),
                        r.get(ISSUE.VERSION),
                        instant(r.get(ISSUE.CREATED_AT)),
                        instant(r.get(ISSUE.UPDATED_AT))));
    }

    // an epic's stories, or a story's subtasks — "children", in rank order
    private Field<List<IssueRef>> subtasksOf(Field<UUID> issueId) {
        var sub = ISSUE.as("sub");
        var subStatus = WORKFLOW_STATUS.as("sub_status");
        return DSL.multiset(DSL.select(sub.ID, sub.KEY, sub.SUMMARY, sub.TYPE.convertFrom(IssueType::fromValue),
                                sub.STATUS_CATEGORY, subStatus.NAME)
                        .from(sub)
                        .join(subStatus).on(subStatus.ID.eq(sub.STATUS_ID))
                        .where(sub.PARENT_ID.eq(issueId), sub.DELETED_AT.isNull())
                        .orderBy(sub.RANK))
                .as("subtasks")
                .convertFrom(rows -> rows.map(Records.mapping(IssueRef::new)));
    }

    // links in both directions: rows where this issue is the source read "blocks", where it's the target "is blocked by"
    private Field<List<LinkView>> linksOf(Field<UUID> issueId, UUID viewerId) {
        var other = ISSUE.as("other");
        var otherStatus = WORKFLOW_STATUS.as("other_status");
        var outward = DSL.select(ISSUE_LINK.ID, ISSUE_LINK.TYPE, DSL.inline(true).as("from_source"), other.ID,
                        other.KEY, other.SUMMARY, other.TYPE, other.STATUS_CATEGORY, otherStatus.NAME)
                .from(ISSUE_LINK)
                .join(other).on(other.ID.eq(ISSUE_LINK.TARGET_ISSUE_ID))
                .join(otherStatus).on(otherStatus.ID.eq(other.STATUS_ID))
                .where(ISSUE_LINK.SOURCE_ISSUE_ID.eq(issueId), other.DELETED_AT.isNull(),
                        // a link may point into a private project the viewer isn't in: don't show its summary
                        ProjectVisibility.visibleTo(other.PROJECT_ID, viewerId));
        var inward = DSL.select(ISSUE_LINK.ID, ISSUE_LINK.TYPE, DSL.inline(false).as("from_source"), other.ID,
                        other.KEY, other.SUMMARY, other.TYPE, other.STATUS_CATEGORY, otherStatus.NAME)
                .from(ISSUE_LINK)
                .join(other).on(other.ID.eq(ISSUE_LINK.SOURCE_ISSUE_ID))
                .join(otherStatus).on(otherStatus.ID.eq(other.STATUS_ID))
                .where(ISSUE_LINK.TARGET_ISSUE_ID.eq(issueId), other.DELETED_AT.isNull(),
                        // a link may point into a private project the viewer isn't in: don't show its summary
                        ProjectVisibility.visibleTo(other.PROJECT_ID, viewerId));
        return DSL.multiset(outward.unionAll(inward))
                .as("links")
                .convertFrom(rows -> rows.map(r -> {
                    LinkType type = LinkType.fromValue(r.value2());
                    IssueRef issue = new IssueRef(r.value4(), r.value5(), r.value6(), IssueType.fromValue(r.value7()),
                            r.value8(), r.value9());
                    return new LinkView(r.value1(), type, type.label(r.value3()), issue);
                }).stream()
                        .sorted(Comparator.comparing(LinkView::label).thenComparing(link -> link.issue().key()))
                        .toList());
    }

    // the files on the issue, oldest first (the order they were added in)
    private Field<List<AttachmentView>> attachmentsOf(Field<UUID> issueId) {
        var uploader = APP_USER.as("uploader");
        return DSL.multiset(DSL.select(ATTACHMENT.ID, ATTACHMENT.FILE_NAME, ATTACHMENT.CONTENT_TYPE,
                                ATTACHMENT.SIZE_BYTES, uploader.ID, uploader.DISPLAY_NAME, uploader.INITIALS,
                                uploader.AVATAR_COLOR, ATTACHMENT.CREATED_AT)
                        .from(ATTACHMENT)
                        .join(uploader).on(uploader.ID.eq(ATTACHMENT.UPLOADER_ID))
                        .where(ATTACHMENT.ISSUE_ID.eq(issueId), ATTACHMENT.DELETED_AT.isNull())
                        .orderBy(ATTACHMENT.CREATED_AT, ATTACHMENT.ID))
                .as("attachments")
                .convertFrom(rows -> rows.map(r -> new AttachmentView(r.value1(), r.value2(), r.value3(), r.value4(),
                        new UserRef(r.value5(), r.value6(), r.value7(), r.value8()), instant(r.value9()))));
    }

    /** One issue as a parent or link shows it; deleted issues are not found. */
    public Optional<IssueRef> ref(UUID issueId) {
        return dsl.select(ISSUE.ID, ISSUE.KEY, ISSUE.SUMMARY, ISSUE.TYPE.convertFrom(IssueType::fromValue),
                        ISSUE.STATUS_CATEGORY, WORKFLOW_STATUS.NAME)
                .from(ISSUE)
                .join(WORKFLOW_STATUS).on(WORKFLOW_STATUS.ID.eq(ISSUE.STATUS_ID))
                .where(ISSUE.ID.eq(issueId), ISSUE.DELETED_AT.isNull())
                .fetchOptional(Records.mapping(IssueRef::new));
    }

    /**
     * The bottom of a list: the highest rank in the backlog ({@code sprintId} null) or in a sprint.
     * The column is COLLATE "C", so MAX compares byte by byte, the same order Rank uses.
     */
    public Optional<String> lastRank(UUID projectId, @Nullable UUID sprintId) {
        return dsl.select(DSL.max(ISSUE.RANK))
                .from(ISSUE)
                .where(ISSUE.PROJECT_ID.eq(projectId),
                        sprintId == null ? ISSUE.SPRINT_ID.isNull() : ISSUE.SPRINT_ID.eq(sprintId),
                        ISSUE.DELETED_AT.isNull())
                .fetchOptional(0, String.class);
    }

    // BLOCK: ranks inside one list — the backlog (sprintId null) or one sprint — of a project, live issues only.
    // excludeId leaves out the issue being moved: its own current rank is not a neighbour.

    /** The rank of {@code key} if it is in this list. */
    public Optional<String> rankInList(UUID projectId, @Nullable UUID sprintId, String key, UUID excludeId) {
        return dsl.select(ISSUE.RANK).from(ISSUE)
                .where(inList(projectId, sprintId, excludeId), ISSUE.KEY.eq(key))
                .fetchOptional(ISSUE.RANK);
    }

    /** The first rank after {@code lower} (the very first when null), or empty at the end of the list. */
    public Optional<String> rankAbove(UUID projectId, @Nullable UUID sprintId, @Nullable String lower, UUID excludeId) {
        return dsl.select(DSL.min(ISSUE.RANK)).from(ISSUE)
                .where(inList(projectId, sprintId, excludeId), lower == null ? DSL.noCondition() : ISSUE.RANK.gt(lower))
                .fetchOptional(0, String.class);
    }

    /** The last rank before {@code upper} (the very last when null), or empty at the top of the list. */
    public Optional<String> rankBelow(UUID projectId, @Nullable UUID sprintId, @Nullable String upper, UUID excludeId) {
        return dsl.select(DSL.max(ISSUE.RANK)).from(ISSUE)
                .where(inList(projectId, sprintId, excludeId), upper == null ? DSL.noCondition() : ISSUE.RANK.lt(upper))
                .fetchOptional(0, String.class);
    }

    /** The first rank of a list, for putting issues at its top. */
    public Optional<String> firstRank(UUID projectId, @Nullable UUID sprintId) {
        return dsl.select(DSL.min(ISSUE.RANK)).from(ISSUE)
                .where(ISSUE.PROJECT_ID.eq(projectId), sprintIs(sprintId), ISSUE.DELETED_AT.isNull())
                .fetchOptional(0, String.class);
    }

    private static Condition inList(UUID projectId, @Nullable UUID sprintId, UUID excludeId) {
        return ISSUE.PROJECT_ID.eq(projectId).and(sprintIs(sprintId)).and(ISSUE.DELETED_AT.isNull())
                .and(ISSUE.ID.ne(excludeId));
    }

    private static Condition sprintIs(@Nullable UUID sprintId) {
        return sprintId == null ? ISSUE.SPRINT_ID.isNull() : ISSUE.SPRINT_ID.eq(sprintId);
    }

    /**
     * Live issues in these statuses — the population of one board column. {@code sprintId} null counts
     * across all sprints (kanban boards have none).
     */
    public int countInStatuses(UUID projectId, List<UUID> statusIds, @Nullable UUID sprintId) {
        return dsl.fetchCount(ISSUE, ISSUE.PROJECT_ID.eq(projectId), ISSUE.STATUS_ID.in(statusIds),
                ISSUE.DELETED_AT.isNull(), sprintId == null ? DSL.noCondition() : ISSUE.SPRINT_ID.eq(sprintId));
    }

    /** A sprint's state (planned, active, closed), if it belongs to the project. */
    public Optional<String> sprintState(UUID projectId, UUID sprintId) {
        return dsl.select(SPRINT.STATE).from(SPRINT)
                .where(SPRINT.ID.eq(sprintId), SPRINT.PROJECT_ID.eq(projectId))
                .fetchOptional(SPRINT.STATE);
    }

    /** Newest first; {@code beforeId} is the cursor (the last id of the previous page). */
    public List<HistoryItem> history(UUID issueId, @Nullable Long beforeId, int limit) {
        return dsl.select(ISSUE_EVENT.ID, ISSUE_EVENT.KIND, ISSUE_EVENT.FIELD, ISSUE_EVENT.OLD_VALUE,
                        ISSUE_EVENT.NEW_VALUE, APP_USER.ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS,
                        APP_USER.AVATAR_COLOR, ISSUE_EVENT.OCCURRED_AT)
                .from(ISSUE_EVENT)
                .join(APP_USER).on(APP_USER.ID.eq(ISSUE_EVENT.ACTOR_ID))
                .where(ISSUE_EVENT.ISSUE_ID.eq(issueId),
                        beforeId == null ? DSL.noCondition() : ISSUE_EVENT.ID.lt(beforeId))
                // ix_issue_event_issue_id_id (issue_id, id DESC): read in index order, stop after limit rows
                .orderBy(ISSUE_EVENT.ID.desc())
                .limit(limit)
                .fetch(r -> new HistoryItem(r.value1(), r.value2(), r.value3(), fromJson(r.value4()),
                        fromJson(r.value5()), new UserRef(r.value6(), r.value7(), r.value8(), r.value9()),
                        instant(r.value10())));
    }

    /** Newest first. */
    public List<WorklogView> worklogs(UUID issueId) {
        return worklogQuery(WORKLOG.ISSUE_ID.eq(issueId));
    }

    public WorklogView worklog(UUID worklogId) {
        return worklogQuery(WORKLOG.ID.eq(worklogId)).getFirst();
    }

    private List<WorklogView> worklogQuery(Condition condition) {
        return dsl.select(WORKLOG.ID, APP_USER.ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS, APP_USER.AVATAR_COLOR,
                        WORKLOG.STARTED_AT, WORKLOG.MINUTES, WORKLOG.NOTE)
                .from(WORKLOG)
                .join(APP_USER).on(APP_USER.ID.eq(WORKLOG.USER_ID))
                .where(condition)
                .orderBy(WORKLOG.STARTED_AT.desc())
                .fetch(IssueQueries::toWorklog);
    }

    private static WorklogView toWorklog(Record8<UUID, UUID, String, String, String, OffsetDateTime, Integer, String> r) {
        return new WorklogView(r.value1(), new UserRef(r.value2(), r.value3(), r.value4(), r.value5()),
                instant(r.value6()), r.value7(), r.value8());
    }

    private @Nullable Object fromJson(@Nullable JSON value) {
        return value == null ? null : json.readValue(value.data(), Object.class);
    }

    private static String projectKeyOf(String issueKey) {
        return issueKey.substring(0, issueKey.lastIndexOf('-'));
    }

    // timestamptz arrives as OffsetDateTime (jOOQ's type for it); the API speaks Instant
    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

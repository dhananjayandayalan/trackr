package dev.trakr.api.home.internal;

import static dev.trakr.jooq.Tables.APP_USER;
import static dev.trakr.jooq.Tables.ISSUE;
import static dev.trakr.jooq.Tables.ISSUE_EVENT;
import static dev.trakr.jooq.Tables.ISSUE_STAR;
import static dev.trakr.jooq.Tables.ISSUE_VIEW;
import static dev.trakr.jooq.Tables.WORKFLOW_STATUS;

import dev.trakr.api.home.HomeViews.ActivityItem;
import dev.trakr.api.home.HomeViews.IssueRow;
import dev.trakr.api.home.HomeViews.Stats;
import dev.trakr.api.home.HomeViews.Tab;
import dev.trakr.api.issue.IssueDetails.UserRef;
import dev.trakr.api.project.ProjectVisibility;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSON;
import org.jooq.Record;
import org.jooq.SelectField;
import org.jooq.SelectJoinStep;
import org.jooq.TableLike;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Your work": read-only queries across issue, issue_event, issue_view, issue_star and
 * workflow_status. Every query filters by ProjectVisibility, so an issue disappears from these lists
 * the moment its project stops being visible to the user.
 */
@Component
public class HomeQueries {

    private static final Duration WORKED_WINDOW = Duration.ofDays(30);
    private static final int VIEWED_LIMIT = 20;

    private final DSLContext dsl;
    private final JsonMapper json;

    HomeQueries(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    /** A cursor position: the row's sort time and id (the id breaks ties between equal times). */
    public record Position(Instant at, UUID id) {
    }

    /**
     * One page of a tab, newest first. Keyset pagination: the next page is "rows strictly after the
     * last one I saw" — {@code (at, id) < (cursorAt, cursorId)} — not OFFSET, so rows added meanwhile
     * don't shift the pages.
     */
    public List<IssueRow> tab(UUID userId, Tab tab, @Nullable Position after, int limit, Instant now) {
        // BLOCK 1: each tab is the same row shape, sorted by a different time from a different table
        Field<OffsetDateTime> at;
        TableLike<?> joined;
        Condition joinOn;
        Condition where;
        switch (tab) {
            case ASSIGNED -> {
                // ix_issue_assignee_open: (assignee_id, updated_at DESC) WHERE open and not deleted
                at = ISSUE.UPDATED_AT;
                joined = null;
                joinOn = null;
                where = ISSUE.ASSIGNEE_ID.eq(userId).and(ISSUE.STATUS_CATEGORY.ne("done"));
            }
            case WORKED -> {
                // my last change to each issue in the window, as a derived table
                var mine = DSL.select(ISSUE_EVENT.ISSUE_ID, DSL.max(ISSUE_EVENT.OCCURRED_AT).as("at"))
                        .from(ISSUE_EVENT)
                        .where(ISSUE_EVENT.ACTOR_ID.eq(userId),
                                ISSUE_EVENT.OCCURRED_AT.ge(now.minus(WORKED_WINDOW).atOffset(ZoneOffset.UTC)))
                        .groupBy(ISSUE_EVENT.ISSUE_ID)
                        .asTable("mine");
                at = mine.field("at", OffsetDateTime.class);
                joined = mine;
                joinOn = mine.field(ISSUE_EVENT.ISSUE_ID).eq(ISSUE.ID);
                where = DSL.noCondition();
            }
            case VIEWED -> {
                at = ISSUE_VIEW.VIEWED_AT;
                joined = ISSUE_VIEW;
                joinOn = ISSUE_VIEW.ISSUE_ID.eq(ISSUE.ID);
                where = ISSUE_VIEW.USER_ID.eq(userId);
            }
            case STARRED -> {
                at = ISSUE_STAR.STARRED_AT;
                joined = ISSUE_STAR;
                joinOn = ISSUE_STAR.ISSUE_ID.eq(ISSUE.ID);
                where = ISSUE_STAR.USER_ID.eq(userId);
            }
            default -> throw new IllegalStateException("unknown tab " + tab);
        }

        // BLOCK 2: shared joins and filters, then keyset paging
        SelectJoinStep<Record> query = dsl.select(List.<SelectField<?>>of(ISSUE.ID, ISSUE.KEY, ISSUE.SUMMARY,
                        ISSUE.TYPE, ISSUE.PRIORITY, ISSUE.STATUS_CATEGORY, ISSUE.ASSIGNEE_ID, WORKFLOW_STATUS.NAME,
                        WORKFLOW_STATUS.COLOR, at))
                .from(ISSUE);
        if (joined != null) {
            query = query.join(joined).on(joinOn);
        }
        Condition page = after == null ? DSL.noCondition()
                : DSL.row(at, ISSUE.ID).lt(after.at().atOffset(ZoneOffset.UTC), after.id());
        Field<OffsetDateTime> sortAt = at;
        return query.join(WORKFLOW_STATUS).on(WORKFLOW_STATUS.ID.eq(ISSUE.STATUS_ID))
                .where(where, page, ISSUE.DELETED_AT.isNull(), ProjectVisibility.visibleTo(ISSUE.PROJECT_ID, userId))
                .orderBy(sortAt.desc(), ISSUE.ID.desc())
                .limit(tab == Tab.VIEWED ? Math.min(limit, VIEWED_LIMIT) : limit)
                .fetch(r -> new IssueRow(r.get(ISSUE.ID), r.get(ISSUE.KEY), projectKeyOf(r.get(ISSUE.KEY)),
                        r.get(ISSUE.SUMMARY), r.get(ISSUE.TYPE), r.get(ISSUE.PRIORITY), r.get(WORKFLOW_STATUS.NAME),
                        r.get(ISSUE.STATUS_CATEGORY), r.get(WORKFLOW_STATUS.COLOR), r.get(ISSUE.ASSIGNEE_ID),
                        r.get(sortAt).toInstant()));
    }

    /**
     * The four tiles in one statement: {@code count(*) FILTER (WHERE …)} counts each tile's subset of
     * the same rows, so Postgres reads my assigned issues once instead of three times.
     *
     * <p>"Waiting on review": open issues of mine in a review step. Categories have no "review" (todo,
     * in progress, done), so it's the status's VOLT colour token "review", which the templates give to
     * In review and Verifying.
     */
    public Stats stats(UUID userId, LocalDate today) {
        LocalDate endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        Condition open = ISSUE.STATUS_CATEGORY.ne("done");
        var counts = dsl.select(
                        DSL.count().filterWhere(open),
                        DSL.count().filterWhere(open.and(ISSUE.DUE_DATE.between(today, endOfWeek))),
                        DSL.count().filterWhere(open.and(WORKFLOW_STATUS.COLOR.eq("review"))))
                .from(ISSUE)
                .join(WORKFLOW_STATUS).on(WORKFLOW_STATUS.ID.eq(ISSUE.STATUS_ID))
                .where(ISSUE.ASSIGNEE_ID.eq(userId), ISSUE.DELETED_AT.isNull(),
                        ProjectVisibility.visibleTo(ISSUE.PROJECT_ID, userId))
                .fetchSingle();
        return new Stats(counts.value1(), counts.value2(), counts.value3(), 0);
    }

    /** The latest history entries across every project the user can see. */
    public List<ActivityItem> activity(UUID userId, int limit) {
        return dsl.select(ISSUE_EVENT.ID, ISSUE_EVENT.KIND, ISSUE_EVENT.FIELD, ISSUE_EVENT.OLD_VALUE,
                        ISSUE_EVENT.NEW_VALUE, APP_USER.ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS,
                        APP_USER.AVATAR_COLOR, ISSUE.KEY, ISSUE.SUMMARY, ISSUE_EVENT.OCCURRED_AT)
                .from(ISSUE_EVENT)
                .join(ISSUE).on(ISSUE.ID.eq(ISSUE_EVENT.ISSUE_ID))
                .join(APP_USER).on(APP_USER.ID.eq(ISSUE_EVENT.ACTOR_ID))
                .where(ISSUE.DELETED_AT.isNull(), ProjectVisibility.visibleTo(ISSUE.PROJECT_ID, userId))
                .orderBy(ISSUE_EVENT.ID.desc())
                .limit(limit)
                .fetch(r -> new ActivityItem(r.value1(), r.value2(), r.value3(), fromJson(r.value4()),
                        fromJson(r.value5()), new UserRef(r.value6(), r.value7(), r.value8(), r.value9()), r.value10(),
                        r.value11(), r.value12().toInstant()));
    }

    private @Nullable Object fromJson(@Nullable JSON value) {
        return value == null ? null : json.readValue(value.data(), Object.class);
    }

    private static String projectKeyOf(String issueKey) {
        return issueKey.substring(0, issueKey.lastIndexOf('-'));
    }
}

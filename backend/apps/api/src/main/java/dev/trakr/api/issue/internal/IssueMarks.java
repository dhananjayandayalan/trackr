package dev.trakr.api.issue.internal;

import static dev.trakr.jooq.Tables.ISSUE_STAR;
import static dev.trakr.jooq.Tables.ISSUE_VIEW;
import static dev.trakr.jooq.Tables.WATCHER;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Rows that are one user's relation to an issue: watching, starred, last viewed. Plain SQL upserts
 * ({@code ON CONFLICT}) instead of entities: a double click, two tabs or a retried PUT must not fail
 * on the primary key, and a JPA merge (SELECT, then INSERT) can't promise that when requests overlap.
 * None of these change the issue itself, so its version stays the same.
 */
@Component
public class IssueMarks {

    private final DSLContext dsl;

    IssueMarks(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void watch(UUID issueId, UUID userId) {
        dsl.insertInto(WATCHER, WATCHER.ISSUE_ID, WATCHER.USER_ID).values(issueId, userId)
                .onConflictDoNothing()
                .execute();
    }

    public void unwatch(UUID issueId, UUID userId) {
        dsl.deleteFrom(WATCHER).where(WATCHER.ISSUE_ID.eq(issueId), WATCHER.USER_ID.eq(userId)).execute();
    }

    public void star(UUID issueId, UUID userId, Instant now) {
        dsl.insertInto(ISSUE_STAR, ISSUE_STAR.USER_ID, ISSUE_STAR.ISSUE_ID, ISSUE_STAR.STARRED_AT)
                .values(userId, issueId, now.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute();
    }

    public void unstar(UUID issueId, UUID userId) {
        dsl.deleteFrom(ISSUE_STAR).where(ISSUE_STAR.USER_ID.eq(userId), ISSUE_STAR.ISSUE_ID.eq(issueId)).execute();
    }

    /** INSERT the first time, move viewed_at forward after that. */
    public void recordView(UUID issueId, UUID userId, Instant now) {
        dsl.insertInto(ISSUE_VIEW, ISSUE_VIEW.USER_ID, ISSUE_VIEW.ISSUE_ID, ISSUE_VIEW.VIEWED_AT)
                .values(userId, issueId, now.atOffset(ZoneOffset.UTC))
                .onConflict(ISSUE_VIEW.USER_ID, ISSUE_VIEW.ISSUE_ID)
                .doUpdate()
                .set(ISSUE_VIEW.VIEWED_AT, now.atOffset(ZoneOffset.UTC))
                .execute();
    }
}

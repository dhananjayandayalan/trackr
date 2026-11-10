package dev.trakr.api.comment.internal;

import static dev.trakr.jooq.Tables.APP_USER;
import static dev.trakr.jooq.Tables.COMMENT;

import dev.trakr.api.comment.CommentView;
import dev.trakr.api.issue.IssueDetails.UserRef;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Reads for the Comments tab, joined with the author's name in one query. */
@Component
public class CommentQueries {

    private final DSLContext dsl;

    CommentQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * An issue's live comments, newest first. Ids are UUIDv7 — time-ordered — so "newest first" is
     * {@code ORDER BY id DESC} and the cursor is just the last id: no tie-breaker column needed.
     */
    public List<CommentView> page(UUID issueId, @Nullable UUID beforeId, int limit) {
        return select(COMMENT.ISSUE_ID.eq(issueId)
                        .and(COMMENT.DELETED_AT.isNull())
                        .and(beforeId == null ? DSL.noCondition() : COMMENT.ID.lt(beforeId)), limit);
    }

    public Optional<CommentView> one(UUID commentId) {
        return select(COMMENT.ID.eq(commentId), 1).stream().findFirst();
    }

    private List<CommentView> select(Condition condition, int limit) {
        return dsl.select(COMMENT.ID, COMMENT.ISSUE_ID, APP_USER.ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS,
                        APP_USER.AVATAR_COLOR, COMMENT.BODY_HTML, COMMENT.CREATED_AT, COMMENT.EDITED_AT,
                        COMMENT.VERSION)
                .from(COMMENT)
                .join(APP_USER).on(APP_USER.ID.eq(COMMENT.AUTHOR_ID))
                .where(condition)
                .orderBy(COMMENT.ID.desc())
                .limit(limit)
                .fetch(r -> new CommentView(r.value1(), r.value2(),
                        new UserRef(r.value3(), r.value4(), r.value5(), r.value6()), r.value7(),
                        r.value8().toInstant(), instant(r.value9()), r.value10()));
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

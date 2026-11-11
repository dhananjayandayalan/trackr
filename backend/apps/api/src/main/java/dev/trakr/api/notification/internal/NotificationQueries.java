package dev.trakr.api.notification.internal;

import static dev.trakr.jooq.Tables.NOTIFICATION;

import dev.trakr.api.notification.NotificationView;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSON;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** jOOQ over the notification table; every query is scoped to one user (the caller). */
@Component
public class NotificationQueries {

    /** Where the next page starts: the last row's (created_at, id). */
    public record Position(OffsetDateTime createdAt, UUID id) {
    }

    private final DSLContext dsl;
    private final JsonMapper json;

    NotificationQueries(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    // BLOCK 1: one page, newest first — ix_notification_user_id_created_at; the id breaks ties in the same instant
    public List<NotificationView> page(UUID userId, boolean unreadOnly, @Nullable Position after, int limit) {
        Condition condition = NOTIFICATION.USER_ID.eq(userId);
        if (unreadOnly) {
            condition = condition.and(NOTIFICATION.READ_AT.isNull());
        }
        if (after != null) {
            condition = condition.and(DSL.row(NOTIFICATION.CREATED_AT, NOTIFICATION.ID)
                    .lt(DSL.row(DSL.val(after.createdAt()), DSL.val(after.id()))));
        }
        return dsl.select(NOTIFICATION.ID, NOTIFICATION.KIND, NOTIFICATION.PAYLOAD, NOTIFICATION.CREATED_AT,
                        NOTIFICATION.READ_AT)
                .from(NOTIFICATION)
                .where(condition)
                .orderBy(NOTIFICATION.CREATED_AT.desc(), NOTIFICATION.ID.desc())
                .limit(limit)
                .fetch(r -> {
                    JsonNode payload = payload(r.value3());
                    return new NotificationView(r.value1(), r.value2(), text(payload, "issueKey"),
                            text(payload, "summary"), text(payload, "actorName"), r.value4().toInstant(),
                            r.value5() == null ? null : r.value5().toInstant());
                });
    }

    // BLOCK 2: the badge — ix_notification_unread is a partial index of exactly these rows
    public int unreadCount(UUID userId) {
        return dsl.fetchCount(NOTIFICATION, NOTIFICATION.USER_ID.eq(userId), NOTIFICATION.READ_AT.isNull());
    }

    // BLOCK 3: mark read — only the caller's own rows, only unread ones (read_at keeps the first time)
    public int markRead(UUID userId, Collection<UUID> ids, OffsetDateTime at) {
        return dsl.update(NOTIFICATION).set(NOTIFICATION.READ_AT, at)
                .where(NOTIFICATION.USER_ID.eq(userId), NOTIFICATION.ID.in(ids), NOTIFICATION.READ_AT.isNull())
                .execute();
    }

    public int markAllRead(UUID userId, OffsetDateTime at) {
        return dsl.update(NOTIFICATION).set(NOTIFICATION.READ_AT, at)
                .where(NOTIFICATION.USER_ID.eq(userId), NOTIFICATION.READ_AT.isNull())
                .execute();
    }

    private JsonNode payload(@Nullable JSON value) {
        return json.readTree(value == null ? "{}" : value.data());
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? "" : node.asString();
    }
}

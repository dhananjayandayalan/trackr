package dev.trakr.api.notification;

import dev.trakr.api.notification.internal.NotificationQueries;
import dev.trakr.api.notification.internal.NotificationQueries.Position;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.TrakrException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 03-api §3.10. A user only ever sees and changes their own notifications: every query takes the caller's id,
 * so there is no "whose is this?" check to forget.
 */
@Service
public class NotificationService {

    static final int PAGE = 20;

    private final NotificationQueries queries;
    private final Clock clock;

    NotificationService(NotificationQueries queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CursorPage<NotificationView> list(UUID userId, boolean unreadOnly, @Nullable String cursor) {
        // one row more than a page: if it exists, there is a next page and the last shown row is its cursor
        List<NotificationView> rows = queries.page(userId, unreadOnly, cursor == null ? null : decode(cursor), PAGE + 1);
        if (rows.size() <= PAGE) {
            return new CursorPage<>(rows, null);
        }
        NotificationView last = rows.get(PAGE - 1);
        return new CursorPage<>(rows.subList(0, PAGE), CursorPage.encode(last.createdAt() + "|" + last.id()));
    }

    @Transactional(readOnly = true)
    public int unreadCount(UUID userId) {
        return queries.unreadCount(userId);
    }

    /** {@code ids} of the caller's notifications; other people's ids are ignored, not an error. @return unread now */
    @Transactional
    public int markRead(UUID userId, Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            queries.markRead(userId, ids, now());
        }
        return queries.unreadCount(userId);
    }

    @Transactional
    public int markAllRead(UUID userId) {
        queries.markAllRead(userId, now());
        return 0;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Position decode(String cursor) {
        String[] parts = CursorPage.decode(cursor).split(java.util.regex.Pattern.quote("|"), 2);
        try {
            return new Position(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (DateTimeParseException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The cursor is not valid.");
        }
    }
}

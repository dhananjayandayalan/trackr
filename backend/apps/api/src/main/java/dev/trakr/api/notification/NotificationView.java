package dev.trakr.api.notification;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of the bell's list. {@code kind}: assigned, mentioned, commented, status_changed. The texts come from
 * the row's payload, written when it was created: the list renders without joining issues or users.
 */
public record NotificationView(UUID id, String kind, String issueKey, String summary, String actorName,
        Instant createdAt, @Nullable Instant readAt) {
}

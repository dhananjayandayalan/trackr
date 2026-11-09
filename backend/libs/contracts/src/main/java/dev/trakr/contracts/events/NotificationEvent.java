package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;

/**
 * Events on {@code trakr.notifications.v1}, produced by the workers' notifier and keyed by the user id:
 * one person's notifications stay in order. Read by the api (/me/stream) and the mailer.
 */
public sealed interface NotificationEvent extends TrakrEvent {

    /** Data: {@code {notificationId, userId, kind, issueKey, actorName, summary}} (04-events §4). */
    record NotificationCreated(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
            UUID notificationId, UUID userId, String kind, UUID issueId, String issueKey, String actorName,
            String summary) implements NotificationEvent {

        public static NotificationCreated of(Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
                UUID notificationId, UUID userId, String kind, UUID issueId, String issueKey, String actorName,
                String summary) {
            return new NotificationCreated(Ids.newId(), occurredAt, actorId, workspaceId, projectId, notificationId,
                    userId, kind, issueId, issueKey, actorName, summary);
        }

        @Override
        public String type() {
            return EventTypes.NOTIFICATION_CREATED;
        }

        @Override
        public UUID aggregateId() {
            return userId;
        }

        @Override
        public String subject() {
            return "notification/" + notificationId;
        }

        // a notification is never changed after it is created
        @Override
        public long sequence() {
            return 0;
        }
    }
}

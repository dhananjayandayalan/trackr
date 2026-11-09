package dev.trakr.api.realtime.internal;

import static dev.trakr.jooq.Tables.NOTIFICATION;

import dev.trakr.api.realtime.internal.SseHub.Frame;
import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.EventTypes;
import dev.trakr.contracts.events.Topics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notifications → the user's own stream (/me/stream): {@code event: notification} with the
 * notification, then {@code event: unread-count} with the new badge number. Like RealtimeConsumer, a
 * group per instance: the user's browser may be connected to any of them.
 */
@Component
class MeStreamConsumer {

    private final SseHub hub;
    private final DSLContext dsl;
    private final JsonMapper json;

    MeStreamConsumer(SseHub hub, DSLContext dsl, JsonMapper json) {
        this.hub = hub;
        this.dsl = dsl;
        this.json = json;
    }

    @KafkaListener(id = "me-stream", topics = Topics.NOTIFICATIONS,
            groupId = "api-me-stream-#{T(java.util.UUID).randomUUID()}", properties = "auto.offset.reset=latest")
    void on(EventEnvelope<Object> envelope) {
        if (!EventTypes.NOTIFICATION_CREATED.equals(envelope.type()) || !(envelope.data() instanceof Map<?, ?> data)) {
            return;
        }
        UUID userId = UUID.fromString(String.valueOf(data.get("userId")));
        Map<String, Object> notification = new LinkedHashMap<>();
        data.forEach((field, value) -> {
            if (!"eventId".equals(field) && !"workspaceId".equals(field)) {
                notification.put(String.valueOf(field), value);
            }
        });
        hub.sendToUser(userId, new Frame(envelope.id().toString(), "notification", json.writeValueAsString(notification)));

        // read-only, the notification table belongs to the workers (02-data-model §6); served by ix_notification_unread
        int unread = dsl.fetchCount(NOTIFICATION, NOTIFICATION.USER_ID.eq(userId), NOTIFICATION.READ_AT.isNull());
        hub.sendToUser(userId, new Frame(null, "unread-count", json.writeValueAsString(Map.of("count", unread))));
    }
}

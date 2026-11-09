package dev.trakr.workers.notify;

import dev.trakr.contracts.events.CommentEvent;
import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.IssueTransitioned;
import dev.trakr.contracts.events.IssueUpdated;
import dev.trakr.contracts.events.NotificationEvent.NotificationCreated;
import dev.trakr.contracts.events.Topics;
import dev.trakr.contracts.events.TrakrEvent;
import dev.trakr.kernel.Ids;
import dev.trakr.workers.kafka.EventPublisher;
import dev.trakr.workers.kafka.ProcessedEvents;
import dev.trakr.workers.notify.NotificationRules.Notice;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue and comment events → notification rows + {@code trakr.notification.created.v1} (one per row,
 * keyed by the recipient). The rows and the processed_event mark commit together; the Kafka sends happen
 * before that commit and wait for the ack (see EventPublisher for why that is at least once).
 */
@Component
class Notifier {

    static final String GROUP = "workers-notifier";

    private final JdbcClient jdbc;
    private final ProcessedEvents processed;
    private final EventPublisher publisher;
    private final JsonMapper json;
    private final Clock clock;

    Notifier(JdbcClient jdbc, ProcessedEvents processed, EventPublisher publisher, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.processed = processed;
        this.publisher = publisher;
        this.json = json;
        this.clock = clock;
    }

    @KafkaListener(id = "notifier", topics = {Topics.ISSUES, Topics.COMMENTS}, groupId = GROUP,
            containerFactory = "recordFactory")
    @Transactional
    public void on(EventEnvelope<TrakrEvent> envelope) {
        processed.runOnce(GROUP, envelope.id(), () -> notify(envelope.data()));
    }

    private void notify(TrakrEvent event) {
        Subject subject = subjectOf(event);
        if (subject == null) {
            return;
        }
        List<Notice> notices = NotificationRules.noticesFor(event, watchersOf(subject.issueId()));
        if (notices.isEmpty()) {
            return;
        }
        String actorName = jdbc.sql("SELECT display_name FROM app_user WHERE id = :id").param("id", event.actorId())
                .query(String.class).optional().orElse("Someone");
        for (Notice notice : notices) {
            UUID notificationId = Ids.newId();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("issueKey", subject.issueKey());
            payload.put("summary", subject.summary());
            payload.put("actorName", actorName);
            jdbc.sql("""
                            INSERT INTO notification (id, user_id, kind, issue_id, actor_id, payload, created_at)
                            VALUES (:id, :user, :kind, :issue, :actor, CAST(:payload AS jsonb), :at)""")
                    .param("id", notificationId)
                    .param("user", notice.userId())
                    .param("kind", notice.kind().value())
                    .param("issue", subject.issueId())
                    .param("actor", event.actorId())
                    .param("payload", json.writeValueAsString(payload))
                    .param("at", Timestamp.from(clock.instant()))
                    .update();
            publisher.publish(Topics.NOTIFICATIONS, notice.userId().toString(), NotificationCreated.of(clock.instant(),
                    event.actorId(), event.workspaceId(), event.projectId(), notificationId, notice.userId(),
                    notice.kind().value(), subject.issueId(), subject.issueKey(), actorName, subject.summary()));
        }
    }

    // what a notification is about: an issue, by id, key and summary
    private record Subject(UUID issueId, String issueKey, String summary) {
    }

    private Subject subjectOf(TrakrEvent event) {
        return switch (event) {
            case IssueUpdated e -> new Subject(e.snapshot().id(), e.snapshot().key(), e.snapshot().summary());
            case IssueTransitioned e -> new Subject(e.snapshot().id(), e.snapshot().key(), e.snapshot().summary());
            case CommentEvent.CommentAdded e -> new Subject(e.issueId(), e.issueKey(), summaryOf(e.issueId()));
            // other events notify nobody (NotificationRules agrees)
            default -> null;
        };
    }

    private String summaryOf(UUID issueId) {
        return jdbc.sql("SELECT summary FROM issue WHERE id = :id").param("id", issueId)
                .query(String.class).optional().orElse("");
    }

    private Set<UUID> watchersOf(UUID issueId) {
        return new HashSet<>(jdbc.sql("SELECT user_id FROM watcher WHERE issue_id = :id").param("id", issueId)
                .query(UUID.class).list());
    }
}

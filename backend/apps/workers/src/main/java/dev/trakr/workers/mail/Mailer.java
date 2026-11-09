package dev.trakr.workers.mail;

import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.NotificationEvent.NotificationCreated;
import dev.trakr.contracts.events.Topics;
import dev.trakr.contracts.events.TrakrEvent;
import dev.trakr.workers.config.WorkersProperties;
import dev.trakr.workers.kafka.ProcessedEvents;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notifications → email. Being assigned or mentioned is mailed at once; the rest (status changes,
 * comments) waits in a Redis list {@code digest:{userId}} for DigestScheduler, so a busy issue doesn't
 * send twenty emails. Deduped by notification id: a redelivered notification mails nobody twice.
 */
@Component
class Mailer {

    static final String GROUP = "workers-mailer";
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    record Recipient(String email, String name) {
    }

    private final JavaMailSender mail;
    private final MailTemplates templates;
    private final JdbcClient jdbc;
    private final ProcessedEvents processed;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final WorkersProperties properties;

    Mailer(JavaMailSender mail, MailTemplates templates, JdbcClient jdbc, ProcessedEvents processed,
            StringRedisTemplate redis, JsonMapper json, WorkersProperties properties) {
        this.mail = mail;
        this.templates = templates;
        this.jdbc = jdbc;
        this.processed = processed;
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    @KafkaListener(id = "mailer", topics = Topics.NOTIFICATIONS, groupId = GROUP, containerFactory = "recordFactory")
    @Transactional
    public void on(EventEnvelope<TrakrEvent> envelope) {
        if (!(envelope.data() instanceof NotificationCreated notification)) {
            return;
        }
        // the notification id, not the envelope id: the same notification published twice is one email
        processed.runOnce(GROUP, notification.notificationId(), () -> handle(notification));
    }

    private void handle(NotificationCreated notification) {
        switch (notification.kind()) {
            case "assigned", "mentioned" -> recipient(notification.userId()).ifPresent(to -> send(to,
                    notification.kind(), Map.of(
                            "userName", to.name(),
                            "actorName", notification.actorName(),
                            "issueKey", notification.issueKey(),
                            "summary", notification.summary(),
                            "link", link(notification.issueKey()))));
            default -> redis.opsForList().rightPush(DigestScheduler.key(notification.userId()),
                    json.writeValueAsString(Map.of("kind", notification.kind(), "issueKey", notification.issueKey(),
                            "summary", notification.summary(), "actorName", notification.actorName())));
        }
    }

    void send(Recipient to, String template, Map<String, String> values) {
        MailTemplates.Mail rendered = templates.render(template, values);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mail().from());
        message.setTo(to.email());
        message.setSubject(rendered.subject());
        message.setText(rendered.body());
        // a failure throws: the transaction rolls back (processed_event too) and the notification is retried
        mail.send(message);
        log.info("mail sent template={} to={}", template, to.email());
    }

    Optional<Recipient> recipient(UUID userId) {
        return jdbc.sql("SELECT email, display_name FROM app_user WHERE id = :id").param("id", userId)
                .query((rs, n) -> new Recipient(rs.getString("email"), rs.getString("display_name")))
                .optional();
    }

    private String link(String issueKey) {
        String projectKey = issueKey.substring(0, issueKey.lastIndexOf('-'));
        return properties.app().origin() + "/p/" + projectKey + "/issues/" + issueKey;
    }
}

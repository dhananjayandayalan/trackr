package dev.trakr.workers.notify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.contracts.events.CommentEvent.CommentAdded;
import dev.trakr.contracts.events.IssueSnapshot;
import dev.trakr.contracts.events.IssueTransitioned;
import dev.trakr.contracts.events.IssueUpdated;
import dev.trakr.contracts.events.Topics;
import dev.trakr.kernel.Ids;
import dev.trakr.workers.Fixtures;
import dev.trakr.workers.Fixtures.World;
import dev.trakr.workers.TestcontainersConfiguration.RecordingMailSender;
import dev.trakr.workers.WorkersIT;
import dev.trakr.workers.kafka.EventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;

/**
 * Event → notification row → notification event → email: the notifier and the mailer together. A
 * mention is mailed at once; a status change waits for the digest.
 */
@WorkersIT
class NotifierIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");

    private final EventPublisher publisher;
    private final JdbcClient jdbc;
    private final RecordingMailSender mail;
    private final StringRedisTemplate redis;

    @Autowired
    NotifierIT(EventPublisher publisher, JdbcClient jdbc, RecordingMailSender mail, StringRedisTemplate redis) {
        this.publisher = publisher;
        this.jdbc = jdbc;
        this.mail = mail;
        this.redis = redis;
    }

    @Test
    void mentionBecomesANotificationAndAnEmail() {
        World w = Fixtures.world(jdbc);
        IssueSnapshot issue = Fixtures.issue(jdbc, w, 1, "Pricing page", null, false);

        // Ada comments and mentions Grace; Ada (author, watcher) gets nothing
        CommentAdded comment = CommentAdded.of(NOW, w.workspace(), w.project(), Ids.newId(), issue.id(), issue.key(),
                w.ada(), "@Grace can you check?", List.of(w.grace()));
        publisher.publish(Topics.COMMENTS, issue.id().toString(), comment);

        Fixtures.await(WAIT, () -> notificationsOf(w, "mentioned") == 1);
        assertThat(notificationsOf(w, "commented")).isZero();
        Map<String, Object> row = jdbc.sql("SELECT user_id, actor_id, payload::text AS payload FROM notification WHERE issue_id = ?")
                .params(issue.id()).query().singleRow();
        assertThat(row.get("user_id")).isEqualTo(w.grace());
        assertThat(row.get("actor_id")).isEqualTo(w.ada());
        assertThat((String) row.get("payload")).contains(issue.key()).contains("Ada Lovelace");

        SimpleMailMessage sent = awaitMailTo("u-" + w.grace() + "@example.com");
        assertThat(sent.getSubject()).isEqualTo("Ada Lovelace mentioned you in " + issue.key());
        assertThat(sent.getText()).contains("Hi Grace Hopper").contains("/p/" + w.key() + "/issues/" + issue.key());

        // the same comment again: one notification, one email
        publisher.publish(Topics.COMMENTS, issue.id().toString(), comment);
        publisher.publish(Topics.COMMENTS, issue.id().toString(), CommentAdded.of(NOW, w.workspace(), w.project(),
                Ids.newId(), issue.id(), issue.key(), w.grace(), "done", List.of(w.ada())));
        Fixtures.await(WAIT, () -> notificationsOf(w, "mentioned") == 2);
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE user_id = ?").params(w.grace())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void assignmentIsMailedAndStatusChangesWaitForTheDigest() {
        World w = Fixtures.world(jdbc);
        IssueSnapshot issue = Fixtures.issue(jdbc, w, 1, "Checkout flow", null, false);
        jdbc.sql("INSERT INTO watcher (issue_id, user_id) VALUES (?, ?)").params(issue.id(), w.grace()).update();

        publisher.publish(Topics.ISSUES, issue.id().toString(), IssueUpdated.of(NOW, w.ada(),
                Map.of("assignee", new IssueUpdated.Change(null, w.grace().toString())), issue));
        SimpleMailMessage assigned = awaitMailTo("u-" + w.grace() + "@example.com");
        assertThat(assigned.getSubject()).isEqualTo("Ada Lovelace assigned " + issue.key() + " to you");

        publisher.publish(Topics.ISSUES, issue.id().toString(),
                IssueTransitioned.of(NOW, w.ada(), w.todo(), Fixtures.markDone(jdbc, w, issue, 1)));
        Fixtures.await(WAIT, () -> notificationsOf(w, "status_changed") == 1);
        Fixtures.await(WAIT, () -> {
            Long queued = redis.opsForList().size("digest:" + w.grace());
            return queued != null && queued == 1;
        });
    }

    private int notificationsOf(World w, String kind) {
        return jdbc.sql("SELECT count(*) FROM notification n JOIN issue i ON i.id = n.issue_id "
                        + "WHERE i.project_id = ? AND n.kind = ?")
                .params(w.project(), kind).query(Integer.class).single();
    }

    private SimpleMailMessage awaitMailTo(String address) {
        Fixtures.await(WAIT, () -> mail.sent.stream().anyMatch(m -> List.of(m.getTo()).contains(address)));
        return mail.sent.stream().filter(m -> List.of(m.getTo()).contains(address)).findFirst().orElseThrow();
    }
}

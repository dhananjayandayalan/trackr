package dev.trakr.flows;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.ApiApplication;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.HistoryItem;
import dev.trakr.api.issue.IssueDetails;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.Template;
import dev.trakr.contracts.events.CollabEvent.CollabSnapshot;
import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.EventTypes;
import dev.trakr.contracts.events.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Collab → api (06-collab §4 step 5): a snapshot on {@code trakr.collab.v1} becomes the issue's description —
 * sanitised, versioned, in history at most once per user per 10 minutes — and leaves again as
 * {@code trakr.issue.description-changed.v1} for the search indexer.
 */
@SpringBootTest(classes = ApiApplication.class)
@Import(TestcontainersConfiguration.class)
class CollabSnapshotIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private final IssueService issues;
    private final ProjectService projects;
    private final IdentityService identities;
    private final KafkaTemplate<String, Object> kafka;
    private final KafkaContainer broker;

    @Autowired
    CollabSnapshotIT(IssueService issues, ProjectService projects, IdentityService identities,
            KafkaTemplate<String, Object> kafka, KafkaContainer broker) {
        this.issues = issues;
        this.projects = projects;
        this.identities = identities;
        this.kafka = kafka;
        this.broker = broker;
    }

    @Test
    void aSnapshotBecomesTheSanitisedDescriptionAndIsPublishedForSearch() throws Exception {
        UserView ada = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        var project = projects.create(ada.id(), new NewProject("Collab", "COL", Template.KANBAN, null, Access.PRIVATE,
                null, "cyan", List.of()));
        IssueDetails before = issues.create(ada.id(), new CreateIssue("COL", IssueType.TASK, "Edit me", "Old text",
                null, null, null, null, null, null, List.of()));

        // BLOCK 1: what a browser's editor produced — with something no browser should ever be allowed to store
        snapshot(ada, project.workspaceId(), project.id(), before.id(),
                "<p>Hello <strong>world</strong><script>alert(1)</script><img src=x onerror=alert(1)></p>", 7);

        await(() -> "Hello world".equals(issues.get(ada.id(), "COL-1").descriptionText()));
        IssueDetails after = issues.get(ada.id(), "COL-1");
        assertThat(after.descriptionHtml()).isEqualTo("<p>Hello <strong>world</strong></p>");
        assertThat(after.version()).isEqualTo(before.version() + 1);

        // BLOCK 2: history — one row, old text → new text
        assertThat(descriptionRows(ada)).singleElement().satisfies(row -> {
            assertThat(row.oldValue()).isEqualTo("Old text");
            assertThat(row.newValue()).isEqualTo("Hello world");
        });

        // BLOCK 3: out again on the issues topic, with the new text in the snapshot the indexer reads
        ConsumerRecord<String, String> event = KafkaProbe.await(broker.getBootstrapServers(), Topics.ISSUES,
                r -> r.key().equals(before.id().toString())
                        && EventTypes.ISSUE_DESCRIPTION_CHANGED.equals(KafkaProbe.header(r, "ce_type")), WAIT);
        assertThat(JsonPath.<String>read(event.value(), "$.data.snapshot.descriptionText")).isEqualTo("Hello world");
        assertThat(JsonPath.<Integer>read(event.value(), "$.sequence")).isEqualTo((int) after.version());

        // BLOCK 4: a second snapshot within 10 minutes changes the text, not the history; the same one again, nothing
        snapshot(ada, project.workspaceId(), project.id(), before.id(), "<p>Hello again</p>", 9);
        await(() -> "Hello again".equals(issues.get(ada.id(), "COL-1").descriptionText()));
        snapshot(ada, project.workspaceId(), project.id(), before.id(), "<p>Hello again</p>", 9);
        snapshot(ada, project.workspaceId(), project.id(), before.id(), "<p></p>", 10);
        await(() -> issues.get(ada.id(), "COL-1").descriptionText() == null);

        IssueDetails emptied = issues.get(ada.id(), "COL-1");
        assertThat(emptied.descriptionHtml()).isNull();
        // three changes (world, again, emptied); the repeat changed nothing
        assertThat(emptied.version()).isEqualTo(before.version() + 3);
        assertThat(descriptionRows(ada)).hasSize(1);
    }

    private void snapshot(UserView actor, java.util.UUID workspaceId, java.util.UUID projectId, java.util.UUID issueId,
            String html, long upto) throws Exception {
        CollabSnapshot snapshot = CollabSnapshot.of(Instant.now(), actor.id(), workspaceId, projectId, issueId,
                "ignored: the api takes the text from the sanitised HTML", html, upto);
        ProducerRecord<String, Object> record = new ProducerRecord<>(Topics.COLLAB, issueId.toString(),
                EventEnvelope.wrap(snapshot, "trakr/collab"));
        record.headers().add("ce_type", snapshot.type().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
    }

    private List<HistoryItem> descriptionRows(UserView ada) {
        return issues.history(ada.id(), "COL-1", null).items().stream()
                .filter(item -> "description".equals(item.field()))
                .toList();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + WAIT);
            }
            Thread.sleep(100);
        }
    }
}

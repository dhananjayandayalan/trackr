package dev.trakr.flows;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.ApiApplication;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.IssueDetails;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.MoveIssue;
import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.ProjectView;
import dev.trakr.api.project.Template;
import dev.trakr.contracts.events.EventTypes;
import dev.trakr.contracts.events.Topics;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The outbox's happy path, end to end: a move commits, Modulith sends the event after commit, and a
 * consumer that knows nothing about our Java classes reads a CloudEvents envelope off the topic.
 */
@SpringBootTest(classes = ApiApplication.class)
@Import(TestcontainersConfiguration.class)
class EventExternalizationIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final IssueService issues;
    private final ProjectService projects;
    private final IdentityService identities;
    private final KafkaContainer kafka;
    private final DSLContext dsl;

    @Autowired
    EventExternalizationIT(IssueService issues, ProjectService projects, IdentityService identities,
            KafkaContainer kafka, DSLContext dsl) {
        this.issues = issues;
        this.projects = projects;
        this.identities = identities;
        this.kafka = kafka;
        this.dsl = dsl;
    }

    @Test
    void movedIssueArrivesOnTheIssuesTopicAsACloudEvent() {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        ProjectView project = projects.create(owner.id(), new NewProject("Events", "EVX", Template.SCRUM, null,
                Access.PRIVATE, null, "cyan", List.of()));
        IssueDetails issue = issues.create(owner.id(), new CreateIssue("EVX", IssueType.TASK, "Move me", null, null,
                null, null, null, null, null, List.of()));
        issues.create(owner.id(), new CreateIssue("EVX", IssueType.TASK, "Neighbour", null, null, null, null, null,
                null, null, List.of()));

        issues.move(owner.id(), "EVX-1", new MoveIssue(null, null, "EVX-2", null, 0));

        // BLOCK 1: the record — key = issue id (its partition), ce_* headers for routing without parsing
        ConsumerRecord<String, String> record = KafkaProbe.await(kafka.getBootstrapServers(), Topics.ISSUES,
                r -> issue.id().toString().equals(r.key())
                        && EventTypes.ISSUE_MOVED.equals(KafkaProbe.header(r, "ce_type")), WAIT);
        String envelope = record.value();

        // BLOCK 2: the envelope (04-events §3)
        assertThat(JsonPath.<String>read(envelope, "$.specversion")).isEqualTo("1.0");
        assertThat(JsonPath.<String>read(envelope, "$.type")).isEqualTo(EventTypes.ISSUE_MOVED);
        assertThat(JsonPath.<String>read(envelope, "$.id")).isEqualTo(KafkaProbe.header(record, "ce_id"));
        assertThat(JsonPath.<String>read(envelope, "$.source")).isEqualTo("trakr/api");
        assertThat(JsonPath.<String>read(envelope, "$.subject")).isEqualTo("issue/" + issue.id());
        assertThat(JsonPath.<String>read(envelope, "$.datacontenttype")).isEqualTo("application/json");
        assertThat(JsonPath.<String>read(envelope, "$.projectid")).isEqualTo(project.id().toString());
        assertThat(JsonPath.<String>read(envelope, "$.workspaceid")).isEqualTo(project.workspaceId().toString());
        assertThat(JsonPath.<String>read(envelope, "$.actorid")).isEqualTo(owner.id().toString());
        assertThat(JsonPath.<Integer>read(envelope, "$.sequence")).isEqualTo(1);
        assertThat(JsonPath.<String>read(envelope, "$.time")).endsWith("Z");
        // BLOCK 3: the data — the event itself, with the snapshot consumers need
        assertThat(JsonPath.<String>read(envelope, "$.data.snapshot.key")).isEqualTo("EVX-1");
        assertThat(JsonPath.<String>read(envelope, "$.data.rank")).isEqualTo(JsonPath.read(envelope, "$.data.snapshot.rank"));

        // BLOCK 4: the outbox row behind it is completed once the broker acknowledged it
        String eventId = JsonPath.read(envelope, "$.id");
        awaitCompleted(eventId);
    }

    @Test
    void projectEventsGoToTheProjectsTopicKeyedByProject() {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        ProjectView project = projects.create(owner.id(), new NewProject("Projects", "PRX", Template.KANBAN, null,
                Access.PRIVATE, null, "cyan", List.of()));

        ConsumerRecord<String, String> record = KafkaProbe.await(kafka.getBootstrapServers(), Topics.PROJECTS,
                r -> project.id().toString().equals(r.key())
                        && EventTypes.PROJECT_CREATED.equals(KafkaProbe.header(r, "ce_type")), WAIT);

        assertThat(JsonPath.<String>read(record.value(), "$.data.snapshot.key")).isEqualTo("PRX");
        assertThat(JsonPath.<String>read(record.value(), "$.subject")).isEqualTo("project/" + project.id());
    }

    private void awaitCompleted(String eventId) {
        var completion = DSL.field("completion_date");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            // the serialized event is the TrakrEvent itself (before mapping), so its eventId is in there
            int completed = dsl.fetchCount(DSL.table("event_publication"),
                    DSL.field("serialized_event", String.class).contains(eventId), completion.isNotNull());
            if (completed == 1) {
                return;
            }
            sleep();
        }
        throw new AssertionError("publication of " + eventId + " was not completed");
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

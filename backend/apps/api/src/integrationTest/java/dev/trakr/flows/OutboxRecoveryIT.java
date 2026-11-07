package dev.trakr.flows;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.api.ApiApplication;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.IssueDetails;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.MoveIssue;
import dev.trakr.api.issue.MoveResult;
import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.Template;
import dev.trakr.contracts.events.EventTypes;
import dev.trakr.contracts.events.Topics;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The whole point of the outbox: Kafka is down, a user moves an issue, the move commits anyway — and the
 * event is not lost. It waits in event_publication until Kafka is back and it is sent again.
 *
 * <p>"Down" = the Kafka container is paused (frozen, same ports), not stopped: a restarted container
 * would get new ports and the producer couldn't find it again. The producer timeouts are short so the
 * first attempt gives up within seconds instead of the default two minutes.
 */
@SpringBootTest(classes = ApiApplication.class, properties = {
        "spring.kafka.producer.properties.max.block.ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=3000"})
@Import(TestcontainersConfiguration.class)
class OutboxRecoveryIT {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecoveryIT.class);

    private final IssueService issues;
    private final ProjectService projects;
    private final IdentityService identities;
    private final IncompleteEventPublications incomplete;
    private final KafkaContainer kafka;
    private final DSLContext dsl;

    @Autowired
    OutboxRecoveryIT(IssueService issues, ProjectService projects, IdentityService identities,
            IncompleteEventPublications incomplete, KafkaContainer kafka, DSLContext dsl) {
        this.issues = issues;
        this.projects = projects;
        this.identities = identities;
        this.incomplete = incomplete;
        this.kafka = kafka;
        this.dsl = dsl;
    }

    @Test
    void eventWrittenWhileKafkaIsDownIsDeliveredWhenItIsBack() {
        // BLOCK 1: a working system: the producer is connected and everything so far is sent
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        projects.create(owner.id(), new NewProject("Outage", "OUT", Template.SCRUM, null, Access.PRIVATE, null, "cyan",
                List.of()));
        IssueDetails issue = issues.create(owner.id(), new CreateIssue("OUT", IssueType.TASK, "Survivor", null, null,
                null, null, null, null, null, List.of()));
        issues.create(owner.id(), new CreateIssue("OUT", IssueType.TASK, "Neighbour", null, null, null, null, null,
                null, null, List.of()));
        waitUntil(Duration.ofSeconds(20), () -> incompleteFor(issue.id().toString()) == 0);

        // BLOCK 2: Kafka goes away; the move still commits — the database doesn't depend on the broker
        pauseKafka();
        MoveResult moved;
        try {
            moved = issues.move(owner.id(), "OUT-1", new MoveIssue(null, null, "OUT-2", null, 0));
            assertThat(moved.issue().version()).isEqualTo(1);

            // the publication is in the outbox, not completed; the send attempt times out after ~3 s
            assertThat(incompleteFor(issue.id().toString())).isEqualTo(1);
            waitUntil(Duration.ofSeconds(15), () -> failedFor(issue.id().toString()) == 1);
            log.info("outbox holds the move while Kafka is down");
        } finally {
            // BLOCK 3: Kafka is back
            unpauseKafka();
        }

        // BLOCK 4: resubmit what's still incomplete (what the scheduled job and a restart also do) → it arrives
        incomplete.resubmitIncompletePublications(publication -> true);
        var record = KafkaProbe.await(kafka.getBootstrapServers(), Topics.ISSUES,
                r -> issue.id().toString().equals(r.key())
                        && EventTypes.ISSUE_MOVED.equals(KafkaProbe.header(r, "ce_type")), Duration.ofSeconds(30));
        assertThat(record.value()).contains("\"sequence\":1");
        waitUntil(Duration.ofSeconds(20), () -> incompleteFor(issue.id().toString()) == 0);
    }

    // publications of events about this issue that Kafka hasn't acknowledged yet
    private int incompleteFor(String issueId) {
        return dsl.fetchCount(DSL.table("event_publication"),
                DSL.field("serialized_event", String.class).contains(issueId),
                DSL.field("completion_date").isNull());
    }

    private int failedFor(String issueId) {
        return dsl.fetchCount(DSL.table("event_publication"),
                DSL.field("serialized_event", String.class).contains(issueId),
                DSL.field("completion_date").isNull(),
                DSL.field("status", String.class).eq("FAILED"));
    }

    private void pauseKafka() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    private void unpauseKafka() {
        kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
    }

    private static void waitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}

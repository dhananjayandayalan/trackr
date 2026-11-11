package dev.trakr.workers.search;

import dev.trakr.workers.TestcontainersConfiguration.OwnerJdbc;
import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import dev.trakr.contracts.events.IssueCreated;
import dev.trakr.contracts.events.IssueDeleted;
import dev.trakr.contracts.events.IssueSnapshot;
import dev.trakr.contracts.events.IssueUpdated;
import dev.trakr.contracts.events.SprintEvent.SprintUpdated;
import dev.trakr.contracts.events.Topics;
import dev.trakr.contracts.events.TrakrEvent;
import dev.trakr.workers.Fixtures;
import dev.trakr.workers.Fixtures.World;
import dev.trakr.workers.WorkersIT;
import dev.trakr.workers.kafka.EventPublisher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Events in Kafka → documents in Elasticsearch, including the cases at-least-once delivery produces:
 * an older event arriving after a newer one, and the same events arriving twice.
 */
@WorkersIT
class SearchIndexerIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");

    private final EventPublisher publisher;
    private final ElasticsearchClient elasticsearch;
    private final JdbcClient jdbc;

    @Autowired
    SearchIndexerIT(EventPublisher publisher, ElasticsearchClient elasticsearch, OwnerJdbc owner) {
        this.publisher = publisher;
        this.elasticsearch = elasticsearch;
        this.jdbc = owner.client();
    }

    @Test
    void newestVersionWinsWhateverTheOrder() {
        World w = Fixtures.world(jdbc);
        IssueSnapshot v0 = Fixtures.issue(jdbc, w, 1, "First title", null, true);
        IssueSnapshot v1 = withSummary(v0, "Second title", 1);
        IssueSnapshot v2 = withSummary(v0, "Third title", 2);

        // created, then the newest update…
        publish(IssueCreated.of(NOW, w.ada(), v0), v0);
        IssueUpdated newest = IssueUpdated.of(NOW, w.ada(), Map.of(), v2);
        publish(newest, v2);
        Fixtures.await(WAIT, () -> "Third title".equals(summaryOf(v0)));

        // …then an older one arrives late (a retry, a resubmitted outbox row): refused with 409, ignored
        IssueUpdated older = IssueUpdated.of(NOW, w.ada(), Map.of(), v1);
        publish(older, v1);
        // and the same newest one again (a redelivery)
        publish(newest, v2);
        awaitProcessed(w);

        GetResponse<IssueDocument> doc = get(v0);
        assertThat(doc.source().summary()).isEqualTo("Third title");
        // external versioning: the document's version is the event's sequence
        assertThat(doc.version()).isEqualTo(2);
        assertThat(doc.source().statusName()).isEqualTo("To do");
        assertThat(doc.source().sprintName()).isEqualTo("Sprint 1");
        assertThat(doc.source().watcherIds()).containsExactly(w.ada());
    }

    @Test
    void deletedIssueIsRemoved() {
        World w = Fixtures.world(jdbc);
        IssueSnapshot v0 = Fixtures.issue(jdbc, w, 1, "Short-lived", null, false);
        publish(IssueCreated.of(NOW, w.ada(), v0), v0);
        Fixtures.await(WAIT, () -> get(v0).found());

        publish(IssueDeleted.of(NOW, w.ada(), withSummary(v0, "Short-lived", 1)), v0);

        Fixtures.await(WAIT, () -> !get(v0).found());
    }

    @Test
    void sprintRenameUpdatesEveryDocumentInTheSprint() throws IOException {
        World w = Fixtures.world(jdbc);
        IssueSnapshot a = Fixtures.issue(jdbc, w, 1, "In the sprint", null, true);
        publish(IssueCreated.of(NOW, w.ada(), a), a);
        Fixtures.await(WAIT, () -> get(a).found());
        // update_by_query only sees documents a refresh has made searchable
        elasticsearch.indices().refresh(r -> r.index(IndexManager.ALIAS));

        jdbc.sql("UPDATE sprint SET name = 'Launch sprint' WHERE id = ?").params(w.sprint()).update();
        publisher.publish(Topics.PROJECTS, w.project().toString(),
                SprintUpdated.of(NOW, w.ada(), Fixtures.sprint(w, "Launch sprint", "active")));

        Fixtures.await(WAIT, () -> "Launch sprint".equals(get(a).source().sprintName()));
    }

    // a marker issue published after everything else, on the same key: once it's in, the earlier events were handled
    private void awaitProcessed(World w) {
        IssueSnapshot marker = Fixtures.issue(jdbc, w, 99, "Marker", null, false);
        publish(IssueCreated.of(NOW, w.ada(), marker), marker);
        Fixtures.await(WAIT, () -> get(marker).found());
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void publish(TrakrEvent event, IssueSnapshot about) {
        publisher.publish(Topics.ISSUES, about.id().toString(), event);
    }

    private String summaryOf(IssueSnapshot s) {
        GetResponse<IssueDocument> doc = get(s);
        return doc.found() ? doc.source().summary() : null;
    }

    private GetResponse<IssueDocument> get(IssueSnapshot s) {
        try {
            return elasticsearch.get(g -> g.index(IndexManager.ALIAS).id(s.id().toString())
                    .routing(s.projectId().toString()), IssueDocument.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static IssueSnapshot withSummary(IssueSnapshot s, String summary, long version) {
        return new IssueSnapshot(s.id(), s.workspaceId(), s.projectId(), s.projectKey(), s.key(), s.number(), s.type(),
                summary, s.descriptionText(), s.statusId(), s.statusCategory(), s.priority(), s.assigneeId(),
                s.reporterId(), s.parentId(), s.sprintId(), s.storyPoints(), s.rank(), List.copyOf(s.labels()),
                s.dueDate(), s.startDate(), s.flagged(), s.originalEstimateMin(), s.timeSpentMin(), s.fixVersion(),
                s.resolvedAt(), s.deleted(), version, s.createdAt(), s.updatedAt());
    }
}

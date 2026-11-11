package dev.trakr.workers.search;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.trakr.contracts.events.Topics;
import dev.trakr.workers.Fixtures;
import dev.trakr.workers.Fixtures.World;
import dev.trakr.workers.WorkersIT;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;

/** Postgres is the source of truth: wipe the index, reindex, and it holds exactly the live issues again. */
@WorkersIT
class ReindexIT {

    private final Reindexer reindexer;
    private final IndexManager indices;
    private final ElasticsearchClient elasticsearch;
    private final JdbcClient jdbc;
    private final KafkaAdmin kafkaAdmin;

    @Autowired
    ReindexIT(Reindexer reindexer, IndexManager indices, ElasticsearchClient elasticsearch, JdbcClient jdbc,
            KafkaAdmin kafkaAdmin) {
        this.reindexer = reindexer;
        this.indices = indices;
        this.elasticsearch = elasticsearch;
        this.jdbc = jdbc;
        this.kafkaAdmin = kafkaAdmin;
    }

    @Test
    void reindexRebuildsTheIndexFromPostgresAndSwitchesTheAlias() throws IOException {
        World w = Fixtures.world(jdbc);
        for (int n = 1; n <= 25; n++) {
            Fixtures.issue(jdbc, w, n, "Issue " + n, null, n % 2 == 0);
        }
        // a deleted issue stays out of the index
        jdbc.sql("UPDATE issue SET deleted_at = now() WHERE project_id = ? AND number = 25").params(w.project()).update();
        String before = indices.currentIndex();

        // the classes before this one publish events and wait only for their own consumer: let the indexer
        // finish theirs first, or a late write lands in the index after the wipe below
        awaitIndexerCaughtUp();

        // wipe: the index no longer matches Postgres
        elasticsearch.deleteByQuery(d -> d.index(IndexManager.ALIAS).query(q -> q.matchAll(m -> m)).refresh(true));
        assertThat(count()).isZero();

        Reindexer.Result result = reindexer.reindex();

        long live = jdbc.sql("SELECT count(*) FROM issue WHERE deleted_at IS NULL").query(Long.class).single();
        assertThat(result.documents()).isEqualTo(live);
        assertThat(result.previousIndex()).isEqualTo(before);
        assertThat(indices.currentIndex()).isEqualTo(result.index()).isNotEqualTo(before);
        assertThat(count()).isEqualTo(live);
        long ours = elasticsearch.count(c -> c.index(IndexManager.ALIAS)
                .query(q -> q.term(t -> t.field("projectId").value(w.project().toString())))).count();
        assertThat(ours).isEqualTo(24);
    }

    /** Waits until the indexer's group has committed every record on its topics (it commits after each batch). */
    private void awaitIndexerCaughtUp() {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            Fixtures.await(Duration.ofSeconds(30), () -> lag(admin) == 0);
        }
    }

    private static long lag(Admin admin) {
        try {
            Map<TopicPartition, OffsetSpec> ends = new HashMap<>();
            admin.describeTopics(List.of(Topics.ISSUES, Topics.COMMENTS, Topics.PROJECTS)).allTopicNames().get()
                    .forEach((topic, description) -> description.partitions().forEach(
                            p -> ends.put(new TopicPartition(topic, p.partition()), OffsetSpec.latest())));
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(SearchIndexer.GROUP)
                    .partitionsToOffsetAndMetadata().get();
            long lag = 0;
            for (var end : admin.listOffsets(ends).all().get().entrySet()) {
                OffsetAndMetadata done = committed.get(end.getKey());
                // auto-offset-reset is earliest: a partition the group never committed starts at 0
                lag += end.getValue().offset() - (done == null ? 0 : done.offset());
            }
            return lag;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long count() throws IOException {
        elasticsearch.indices().refresh(r -> r.index(IndexManager.ALIAS));
        return elasticsearch.count(c -> c.index(IndexManager.ALIAS)).count();
    }
}

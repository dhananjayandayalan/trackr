package dev.trakr.workers.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.trakr.contracts.events.IssueCreated;
import dev.trakr.contracts.events.IssueSnapshot;
import dev.trakr.contracts.events.Topics;
import dev.trakr.workers.Fixtures;
import dev.trakr.workers.Fixtures.World;
import dev.trakr.workers.WorkersIT;
import dev.trakr.workers.search.IndexManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A poison pill — bytes that aren't an envelope — must not stop the partition: it goes to
 * {@code trakr.issues.v1.dlt} at once (not retryable), and the events behind it are processed normally.
 */
@WorkersIT
class DeadLetterIT {

    private final KafkaTemplate<String, Object> kafka;
    private final EventPublisher publisher;
    private final ElasticsearchClient elasticsearch;
    private final JdbcClient jdbc;
    private final KafkaContainer container;

    @Autowired
    DeadLetterIT(KafkaTemplate<String, Object> kafka, EventPublisher publisher, ElasticsearchClient elasticsearch,
            JdbcClient jdbc, KafkaContainer container) {
        this.kafka = kafka;
        this.publisher = publisher;
        this.elasticsearch = elasticsearch;
        this.jdbc = jdbc;
        this.container = container;
    }

    @Test
    void poisonMessageGoesToTheDltWithoutBlockingTheNextOnes() {
        World w = Fixtures.world(jdbc);
        IssueSnapshot issue = Fixtures.issue(jdbc, w, 1, "Behind the poison", null, false);
        String key = issue.id().toString();
        String poison = "{not json " + UUID.randomUUID();

        // same key = same partition: the valid event really queues up behind the poison
        kafka.send(Topics.ISSUES, key, poison.getBytes(StandardCharsets.UTF_8)).join();
        publisher.publish(Topics.ISSUES, key, IssueCreated.of(Instant.now(), w.ada(), issue));

        // the event behind it is indexed…
        Fixtures.await(Duration.ofSeconds(30), () -> indexed(issue));
        // …and the poison is in the DLT, with the exception the recoverer recorded
        ConsumerRecord<String, byte[]> parked = awaitDeadLetter(poison);
        assertThat(parked.key()).isEqualTo(key);
        assertThat(new String(parked.headers().lastHeader("kafka_dlt-exception-fqcn").value(), StandardCharsets.UTF_8))
                .contains("DeserializationException");
        assertThat(new String(parked.headers().lastHeader("kafka_dlt-original-topic").value(), StandardCharsets.UTF_8))
                .isEqualTo(Topics.ISSUES);
    }

    private boolean indexed(IssueSnapshot issue) {
        try {
            return elasticsearch.exists(e -> e.index(IndexManager.ALIAS).id(issue.id().toString())
                    .routing(issue.projectId().toString())).value();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ConsumerRecord<String, byte[]> awaitDeadLetter(String poison) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, container.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(Topics.deadLetter(Topics.ISSUES)));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    if (poison.equals(new String(record.value(), StandardCharsets.UTF_8))) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("poison message not found in the DLT");
    }
}

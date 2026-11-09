package dev.trakr.workers.kafka;

import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.TrakrEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Events the workers produce (notifications), in the same CloudEvents envelope and with the same
 * {@code ce_*} headers as the api's outbox sends.
 *
 * <p>No outbox here: the send happens inside the consumer's database transaction and waits for the ack.
 * If it fails, the transaction rolls back (processed_event too) and the source event is retried; if the
 * commit fails after a successful send, the retry sends again — at least once, never zero. Consumers of
 * notifications dedupe by notification id.
 */
@Component
public class EventPublisher {

    static final String SOURCE = "trakr/workers";
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, Object> kafka;

    EventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    public void publish(String topic, String key, TrakrEvent event) {
        EventEnvelope<TrakrEvent> envelope = EventEnvelope.wrap(event, SOURCE);
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, envelope);
        record.headers().add("ce_type", bytes(envelope.type()));
        record.headers().add("ce_id", bytes(envelope.id().toString()));
        record.headers().add("ce_specversion", bytes(envelope.specversion()));
        record.headers().add("ce_source", bytes(SOURCE));
        try {
            kafka.send(record).get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing " + envelope.type(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not publish " + envelope.type(), e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

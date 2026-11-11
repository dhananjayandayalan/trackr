package dev.trakr.workers.kafka;

import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.Topics;
import dev.trakr.contracts.events.TrakrEvent;
import dev.trakr.workers.config.WorkersProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * How the workers read and write Kafka (04-events §5, §7): one consumer factory that turns envelopes into
 * typed events, two listener factories (record at a time; whole batches for the indexer), one error
 * policy — retry with back-off, then park the record in {@code <topic>.dlt} — and the topics we own.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfig {

    private final JsonMapper json;

    KafkaConsumerConfig(JsonMapper json) {
        this.json = json;
    }

    /**
     * Boot builds the consumer and producer factories — including where Kafka is, which may come from
     * spring.kafka.bootstrap-servers or from a Testcontainers service connection — and we only swap in our
     * (de)serializers. Building the factories by hand from KafkaProperties would silently ignore a service
     * connection and talk to localhost:9092 instead.
     */
    @Bean
    @SuppressWarnings("unchecked")
    DefaultKafkaConsumerFactoryCustomizer envelopeDeserializers() {
        // a record that isn't a valid envelope comes out as null + an exception header, not as an exception
        // thrown inside poll() that would stop the consumer on the same offset forever ("poison pill")
        return factory -> {
            var typed = (DefaultKafkaConsumerFactory<String, EventEnvelope<TrakrEvent>>) factory;
            typed.setKeyDeserializer(new StringDeserializer());
            typed.setValueDeserializer(new ErrorHandlingDeserializer<>(new EnvelopeDeserializer(json)));
        };
    }

    /**
     * Values are envelopes (as JSON) or raw bytes: the dead-letter recoverer republishes the original
     * bytes of a record it couldn't read. The serializer picks by the value's type, byte[] first.
     */
    @Bean
    @SuppressWarnings("unchecked")
    DefaultKafkaProducerFactoryCustomizer envelopeSerializers() {
        return factory -> {
            Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
            byType.put(byte[].class, new ByteArraySerializer());
            JacksonJsonSerializer<Object> jsonSerializer = new JacksonJsonSerializer<>(json);
            jsonSerializer.setAddTypeInfo(false);
            byType.put(Object.class, jsonSerializer);
            var typed = (DefaultKafkaProducerFactory<String, Object>) factory;
            typed.setKeySerializer(new StringSerializer());
            typed.setValueSerializer(new DelegatingByTypeSerializer(byType, true));
        };
    }

    // BLOCK 1: dead-letter recoverer — same partition number in the .dlt topic, so the DLT keeps the key order
    @Bean
    DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<Object, Object> template) {
        return new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(Topics.deadLetter(record.topic()), record.partition()));
    }

    // BLOCK 2: error handler — 1 s, 2 s, 4 s, 8 s, then the DLT; some failures are hopeless from the start
    @Bean
    DefaultErrorHandler errorHandler(DeadLetterPublishingRecoverer recoverer) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // the same bytes will fail the same way every time: retrying only delays the records behind it
        handler.addNotRetryableExceptions(DeserializationException.class, IllegalArgumentException.class,
                UnknownEventTypeException.class);
        return handler;
    }

    // BLOCK 3: record factory — notifier, report projector, mailer: one record, one transaction, one commit
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<TrakrEvent>> recordFactory(
            ConsumerFactory<Object, Object> consumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<TrakrEvent>> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(typed(consumerFactory));
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        // phase 18: these factories are ours, so spring.kafka.listener.observation-enabled doesn't reach them.
        // On, each record's handling becomes a span that continues the trace in its traceparent header.
        factory.getContainerProperties().setObservationEnabled(true);
        // three consumers per group, one per partition (04-events §4: 3 partitions locally)
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    // BLOCK 4: batch factory — the search indexer: up to 500 records per poll go out as one bulk request
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<TrakrEvent>> batchFactory(
            ConsumerFactory<Object, Object> consumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<TrakrEvent>> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(typed(consumerFactory));
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(AckMode.BATCH);
        // a batch has no single parent: each record's trace gets a link in the batch's span
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    // BLOCK 5: topics — the notifications we produce, and a dead-letter topic per topic we consume
    @Bean
    NewTopic notificationsTopic(WorkersProperties properties) {
        return topic(Topics.NOTIFICATIONS, properties, Duration.ofDays(3));
    }

    @Bean
    NewTopic issuesDeadLetters(WorkersProperties properties) {
        return topic(Topics.deadLetter(Topics.ISSUES), properties, Duration.ofDays(14));
    }

    @Bean
    NewTopic projectsDeadLetters(WorkersProperties properties) {
        return topic(Topics.deadLetter(Topics.PROJECTS), properties, Duration.ofDays(14));
    }

    @Bean
    NewTopic commentsDeadLetters(WorkersProperties properties) {
        return topic(Topics.deadLetter(Topics.COMMENTS), properties, Duration.ofDays(14));
    }

    @Bean
    NewTopic notificationsDeadLetters(WorkersProperties properties) {
        return topic(Topics.deadLetter(Topics.NOTIFICATIONS), properties, Duration.ofDays(14));
    }

    // Boot's factory is declared <?, ?>; envelopeDeserializers() above made it produce these types
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ConsumerFactory<String, EventEnvelope<TrakrEvent>> typed(ConsumerFactory<Object, Object> factory) {
        return (ConsumerFactory) factory;
    }

    // the same partition count as the source topics: the recoverer writes to the record's own partition number
    private static NewTopic topic(String name, WorkersProperties properties, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(properties.events().partitions())
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retention.toMillis()))
                .build();
    }
}

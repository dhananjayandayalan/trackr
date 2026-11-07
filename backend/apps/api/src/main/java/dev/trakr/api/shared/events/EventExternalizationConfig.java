package dev.trakr.api.shared.events;

import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.IssueEvent;
import dev.trakr.contracts.events.ProjectEvent;
import dev.trakr.contracts.events.Topics;
import dev.trakr.contracts.events.TrakrEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/**
 * Which published events leave the process, where they go and what they look like on the wire. This
 * is the only place that knows about Kafka topics: services just publish their events, and the
 * contracts stay free of Spring annotations.
 *
 * <p>How an event gets to Kafka: {@code publishEvent} inside the service's transaction → Modulith
 * writes an event_publication row in that same transaction (the outbox) → after commit, the
 * externalizer sends it with the KafkaTemplate → on the broker's ack the row is marked completed. A
 * crash in between leaves the row incomplete, and it is sent again (at least once, never zero times).
 */
@Configuration(proxyBeanMethods = false)
class EventExternalizationConfig {

    static final String SOURCE = "trakr/api";

    @Bean
    EventExternalizationConfiguration eventExternalization() {
        return EventExternalizationConfiguration.externalizing()
                // BLOCK 1: which events leave the process — the contracts' events, not in-process ones like ProjectCreated
                .select(event -> event instanceof TrakrEvent)
                // BLOCK 2: routing, decided on the original event. The key picks the partition: same key, same
                // partition, so one issue's (or one project's) events stay in order for every consumer.
                .route(IssueEvent.class, event -> RoutingTarget.forTarget(Topics.ISSUES)
                        .andKey(event.aggregateId().toString()))
                .route(ProjectEvent.class, event -> RoutingTarget.forTarget(Topics.PROJECTS)
                        .andKey(event.projectId().toString()))
                // BLOCK 3: what is sent — the CloudEvents envelope around the event
                .mapping(TrakrEvent.class, event -> EventEnvelope.wrap(event, SOURCE))
                // BLOCK 4: headers, computed on what is sent (the envelope): consumers can route on ce_type without
                // parsing the body. byte[] values pass through Kafka's header mapper unchanged.
                .headers(EventExternalizationConfig::headers)
                .build();
    }

    static Map<String, Object> headers(Object payload) {
        if (payload instanceof EventEnvelope<?> envelope) {
            return Map.of("ce_type", bytes(envelope.type()), "ce_id", bytes(envelope.id().toString()),
                    "ce_specversion", bytes(envelope.specversion()), "ce_source", bytes(envelope.source()));
        }
        return Map.of();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

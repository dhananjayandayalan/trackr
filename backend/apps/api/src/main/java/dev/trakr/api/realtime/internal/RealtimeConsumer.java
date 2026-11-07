package dev.trakr.api.realtime.internal;

import dev.trakr.api.realtime.internal.SseHub.Frame;
import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.EventTypes;
import dev.trakr.contracts.events.Topics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka → browsers. Every api instance reads every event (its group id is random: a group of one),
 * because the browsers watching a project may be connected to any instance. Compare the search
 * indexer (phase 10): one fixed group, so the instances split the work and each event is indexed once.
 *
 * <p>{@code auto.offset.reset=latest}: a new instance starts at "now". What a reconnecting browser
 * missed comes from the replay buffer, not from Kafka history.
 */
@Component
class RealtimeConsumer {

    private static final Logger log = LoggerFactory.getLogger(RealtimeConsumer.class);
    // envelope metadata repeated inside data, and ids the browser has no use for
    private static final Set<String> PRIVATE_FIELDS = Set.of("eventId", "occurredAt", "workspaceId");

    private final SseHub hub;
    private final ReplayBuffer replay;
    private final JsonMapper json;

    RealtimeConsumer(SseHub hub, ReplayBuffer replay, JsonMapper json) {
        this.hub = hub;
        this.replay = replay;
        this.json = json;
    }

    @KafkaListener(id = "realtime", topics = {Topics.ISSUES, Topics.PROJECTS, Topics.COMMENTS},
            groupId = "api-realtime-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    void on(EventEnvelope<Object> envelope) {
        if (envelope.projectid() == null) {
            return;
        }
        Frame frame = new Frame(envelope.id().toString(), EventTypes.sseName(envelope.type()), payload(envelope));
        replay.append(envelope.projectid(), frame);
        hub.broadcast(envelope.projectid(), frame);
        log.debug("sse broadcast type={} projectId={} sequence={}", envelope.type(), envelope.projectid(),
                envelope.sequence());
    }

    // the event's data without the private fields, plus the sequence the browser compares with its cache (03-api §4)
    private String payload(EventEnvelope<Object> envelope) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (envelope.data() instanceof Map<?, ?> map) {
            map.forEach((field, value) -> {
                if (!PRIVATE_FIELDS.contains(String.valueOf(field))) {
                    data.put(String.valueOf(field), value);
                }
            });
        }
        data.put("sequence", envelope.sequence());
        return json.writeValueAsString(data);
    }
}

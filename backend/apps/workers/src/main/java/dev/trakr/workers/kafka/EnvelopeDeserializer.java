package dev.trakr.workers.kafka;

import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.EventTypes;
import dev.trakr.contracts.events.TrakrEvent;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.common.serialization.Deserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bytes → {@code EventEnvelope<TrakrEvent>} with {@code data} as the real record ({@code IssueMoved}…),
 * so listeners can {@code switch} over the sealed hierarchy. The envelope's {@code type} says which
 * record {@code data} is — Jackson alone can't know that from the data's JSON.
 *
 * <p>Throws on bad input; ErrorHandlingDeserializer, which wraps this one, catches that and hands the
 * record to the error handler instead of failing the whole poll.
 */
public class EnvelopeDeserializer implements Deserializer<EventEnvelope<TrakrEvent>> {

    private final JsonMapper json;

    public EnvelopeDeserializer(JsonMapper json) {
        this.json = json;
    }

    @Override
    public EventEnvelope<TrakrEvent> deserialize(String topic, byte[] bytes) {
        JsonNode root = json.readTree(bytes);
        String type = root.path("type").asString();
        Class<? extends TrakrEvent> eventClass = EventTypes.classOf(type)
                .orElseThrow(() -> new UnknownEventTypeException(type));
        TrakrEvent data = json.treeToValue(root.required("data"), eventClass);
        return new EventEnvelope<>(
                root.path("specversion").asString(),
                UUID.fromString(root.required("id").asString()),
                type,
                root.path("source").asString(),
                root.path("subject").asString(),
                Instant.parse(root.required("time").asString()),
                root.path("datacontenttype").asString(),
                UUID.fromString(root.required("workspaceid").asString()),
                root.path("projectid").isNull() || root.path("projectid").isMissingNode() ? null
                        : UUID.fromString(root.path("projectid").asString()),
                UUID.fromString(root.required("actorid").asString()),
                root.path("sequence").asLong(),
                data);
    }
}

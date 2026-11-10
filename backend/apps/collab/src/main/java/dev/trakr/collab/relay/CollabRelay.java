package dev.trakr.collab.relay;

import dev.trakr.collab.config.CollabInstance;
import dev.trakr.collab.protocol.AwarenessCodec;
import dev.trakr.collab.protocol.MessageWriter;
import dev.trakr.collab.protocol.ProtocolException;
import dev.trakr.collab.room.CheckpointScheduler;
import dev.trakr.collab.room.Room;
import dev.trakr.collab.room.RoomRegistry;
import dev.trakr.collab.security.Connection;
import dev.trakr.contracts.events.CollabEvent;
import dev.trakr.contracts.events.CollabEvent.CollabAwareness;
import dev.trakr.contracts.events.CollabEvent.CollabSnapshot;
import dev.trakr.contracts.events.CollabEvent.CollabUpdate;
import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.EventTypes;
import dev.trakr.contracts.events.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka between collab instances, and from collab to the api (06-collab §6, 04-events §4). Topic
 * {@code trakr.collab.v1}, keyed by issue id, in the CloudEvents envelope every TRAKR producer uses.
 *
 * <p>BLOCK 1: out. No outbox here, unlike the api: an update is already stored when it is published, and a relay
 * message lost in a crash only means other instances' clients get that edit on their next resync instead of now.
 * Sends are fire-and-forget; a failure is logged, never thrown into the socket handler.
 *
 * <p>BLOCK 2: in. Every instance consumes the topic in a group of its own (the group id contains the instance id),
 * so each one sees every message — a broadcast, not a work queue. It skips what it published itself, and rooms it
 * has no sockets for.
 */
@Component
public class CollabRelay {

    private static final Logger log = LoggerFactory.getLogger(CollabRelay.class);
    static final String SOURCE = "trakr/collab";

    private final KafkaTemplate<String, Object> kafka;
    private final JsonMapper json;
    private final String instanceId;
    private final RoomRegistry rooms;
    private final CheckpointScheduler checkpoints;
    private final Clock clock;

    CollabRelay(KafkaTemplate<String, Object> kafka, JsonMapper json, CollabInstance instance, RoomRegistry rooms,
            CheckpointScheduler checkpoints, Clock clock) {
        this.kafka = kafka;
        this.json = json;
        this.instanceId = instance.id();
        this.rooms = rooms;
        this.checkpoints = checkpoints;
        this.clock = clock;
    }

    // BLOCK 1: out
    public void publishUpdate(Connection from, byte[] update, long updateId, @Nullable Long clientId) {
        send(CollabUpdate.of(clock.instant(), from.userId(), from.grant().workspaceId(), from.grant().projectId(),
                from.issueId(), update, updateId, clientId == null ? 0 : clientId, instanceId));
    }

    public void publishAwareness(Connection from, byte[] awareness) {
        send(CollabAwareness.of(clock.instant(), from.userId(), from.grant().workspaceId(), from.grant().projectId(),
                from.issueId(), awareness, instanceId));
    }

    /** For the api (group api-collab-snapshots): it stores the text and HTML on the issue. */
    public void publishSnapshot(Connection from, String text, String html, long uptoUpdateId) {
        send(CollabSnapshot.of(clock.instant(), from.userId(), from.grant().workspaceId(), from.grant().projectId(),
                from.issueId(), text, html, uptoUpdateId));
    }

    private void send(CollabEvent event) {
        EventEnvelope<CollabEvent> envelope = EventEnvelope.wrap(event, SOURCE);
        ProducerRecord<String, Object> record = new ProducerRecord<>(Topics.COLLAB, event.issueId().toString(),
                envelope);
        record.headers().add("ce_type", bytes(envelope.type()));
        record.headers().add("ce_id", bytes(envelope.id().toString()));
        record.headers().add("ce_specversion", bytes(envelope.specversion()));
        record.headers().add("ce_source", bytes(SOURCE));
        try {
            kafka.send(record).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.warn("could not relay {} for issue {}: {}", event.type(), event.issueId(), failure.getMessage());
                }
            });
        } catch (RuntimeException e) {
            // send() itself throws when the producer can't even get metadata in max.block.ms
            log.warn("could not relay {} for issue {}: {}", event.type(), event.issueId(), e.getMessage());
        }
    }

    // BLOCK 2: in — the header says the type, so snapshots (for the api) are skipped without parsing the body
    @KafkaListener(id = "collab-relay", topics = Topics.COLLAB, groupId = "collab-relay-#{@collabInstance.id()}")
    void on(ConsumerRecord<String, byte[]> record) {
        String type = header(record, "ce_type");
        if (!EventTypes.COLLAB_UPDATE.equals(type) && !EventTypes.COLLAB_AWARENESS.equals(type)) {
            return;
        }
        JsonNode data;
        try {
            data = json.readTree(record.value()).path("data");
        } catch (JacksonException e) {
            log.warn("skipping unreadable collab record at offset {}: {}", record.offset(), e.getOriginalMessage());
            return;
        }
        if (instanceId.equals(data.path("originInstance").asString())) {
            return;
        }
        Room room = rooms.find(UUID.fromString(data.path("issueId").asString())).orElse(null);
        if (room == null) {
            return;
        }
        if (EventTypes.COLLAB_UPDATE.equals(type)) {
            CollabUpdate update = json.treeToValue(data, CollabUpdate.class);
            // the same order rule as for a local update: broadcast first, then record the id (Room BLOCK 1)
            room.locked(() -> {
                room.broadcast(MessageWriter.update(update.update()), null);
                room.noteUpdate(update.updateId());
            });
            checkpoints.touch(room);
        } else {
            CollabAwareness awareness = json.treeToValue(data, CollabAwareness.class);
            try {
                var entries = AwarenessCodec.decode(awareness.awareness());
                room.locked(() -> {
                    if (room.applyAwareness(entries, null)) {
                        room.broadcast(MessageWriter.awareness(awareness.awareness()), null);
                    }
                });
            } catch (ProtocolException e) {
                log.warn("skipping bad awareness from instance {}: {}", awareness.originInstance(), e.getMessage());
            }
        }
    }

    private static @Nullable String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

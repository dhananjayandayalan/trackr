package dev.trakr.contracts.events;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * BLOCK 3: the CloudEvents 1.0 envelope, structured mode (04-events §3): metadata at the top level,
 * the event itself under {@code data}. Field names are CloudEvents' (all lowercase, no separators —
 * the spec forbids anything else in attribute names), so the JSON is
 * {@code {"specversion":"1.0","id":…,"type":…,"workspaceid":…,"data":{…}}}.
 *
 * <p>Producers send {@code EventEnvelope<TrakrEvent>}. A consumer that only routes (the realtime
 * stream) reads {@code EventEnvelope<Object>}, where {@code data} arrives as a JSON map; one that
 * needs the record converts {@code data} to the class the {@code type} names.
 */
public record EventEnvelope<T>(
        String specversion,
        UUID id,
        String type,
        String source,
        String subject,
        Instant time,
        String datacontenttype,
        UUID workspaceid,
        @Nullable UUID projectid,
        UUID actorid,
        long sequence,
        T data) {

    public static <T extends TrakrEvent> EventEnvelope<T> wrap(T event, String source) {
        return new EventEnvelope<>("1.0", event.eventId(), event.type(), source, event.subject(), event.occurredAt(),
                "application/json", event.workspaceId(), event.projectId(), event.actorId(), event.sequence(), event);
    }
}

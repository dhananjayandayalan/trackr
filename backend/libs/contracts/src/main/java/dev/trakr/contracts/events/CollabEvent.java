package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;

/**
 * Events on {@code trakr.collab.v1}, keyed by the issue id, produced by the collab app (04-events §4, 06-collab §6).
 *
 * <ul>
 * <li>{@link CollabUpdate} and {@link CollabAwareness}: the cross-instance relay. Every collab instance reads them
 * and forwards the bytes to its own sockets in that issue's room, unless it sent them itself
 * ({@code originInstance}). Byte arrays travel as base64 strings (Jackson's default for {@code byte[]}).</li>
 * <li>{@link CollabSnapshot}: the document's text and HTML after a checkpoint; the api stores them on the issue.</li>
 * </ul>
 *
 * <p>The envelope fields: the actor is the user whose browser sent the frame, the aggregate is the issue.
 */
public sealed interface CollabEvent extends TrakrEvent {

    UUID issueId();

    @Override
    default UUID aggregateId() {
        return issueId();
    }

    @Override
    default String subject() {
        return "issue/" + issueId();
    }

    /**
     * One Yjs update, already stored as {@code collab_update.id = updateId} by the instance that received it.
     * {@code clientId}: the sender's Y.Doc client id, 0 when not known yet. {@code sequence} = {@code updateId}.
     */
    record CollabUpdate(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
            UUID issueId, byte[] update, long updateId, long clientId, String originInstance) implements CollabEvent {

        public static CollabUpdate of(Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
                UUID issueId, byte[] update, long updateId, long clientId, String originInstance) {
            return new CollabUpdate(Ids.newId(), occurredAt, actorId, workspaceId, projectId, issueId, update,
                    updateId, clientId, originInstance);
        }

        @Override
        public String type() {
            return EventTypes.COLLAB_UPDATE;
        }

        @Override
        public long sequence() {
            return updateId;
        }
    }

    /** An awareness update (who is here, where their caret is): relayed, never stored, no order to keep. */
    record CollabAwareness(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
            UUID issueId, byte[] awareness, String originInstance) implements CollabEvent {

        public static CollabAwareness of(Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
                UUID issueId, byte[] awareness, String originInstance) {
            return new CollabAwareness(Ids.newId(), occurredAt, actorId, workspaceId, projectId, issueId, awareness,
                    originInstance);
        }

        @Override
        public String type() {
            return EventTypes.COLLAB_AWARENESS;
        }

        @Override
        public long sequence() {
            return 0;
        }
    }

    /**
     * A checkpoint was stored: {@code text}/{@code html} as the browser's editor produced them (untrusted — the
     * api sanitises), covering every update up to {@code uptoUpdateId}. {@code sequence} = {@code uptoUpdateId},
     * so a late or duplicated snapshot is recognisably older.
     */
    record CollabSnapshot(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
            UUID issueId, String text, String html, long uptoUpdateId) implements CollabEvent {

        public static CollabSnapshot of(Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
                UUID issueId, String text, String html, long uptoUpdateId) {
            return new CollabSnapshot(Ids.newId(), occurredAt, actorId, workspaceId, projectId, issueId, text, html,
                    uptoUpdateId);
        }

        @Override
        public String type() {
            return EventTypes.COLLAB_SNAPSHOT;
        }

        @Override
        public long sequence() {
            return uptoUpdateId;
        }
    }
}

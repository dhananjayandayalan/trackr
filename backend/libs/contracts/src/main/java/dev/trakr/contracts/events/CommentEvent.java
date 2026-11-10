package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Events on {@code trakr.comments.v1}, keyed by the issue id (an issue's comments stay in order).
 * Data: {@code {commentId, issueId, issueKey, authorId, bodyText, mentionedUserIds[]}} (04-events §4).
 * Defined in phase 10 for the workers' rules and tests; the api publishes them from phase 15.
 * {@code sequence}: the comment's version (0 when added).
 */
public sealed interface CommentEvent extends TrakrEvent {

    UUID commentId();

    UUID issueId();

    String issueKey();

    UUID authorId();

    String bodyText();

    List<UUID> mentionedUserIds();

    @Override
    default UUID aggregateId() {
        return issueId();
    }

    @Override
    default String subject() {
        return "comment/" + commentId();
    }

    record CommentAdded(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
            UUID commentId, UUID issueId, String issueKey, UUID authorId, String bodyText, List<UUID> mentionedUserIds,
            long sequence) implements CommentEvent {

        public CommentAdded {
            mentionedUserIds = List.copyOf(mentionedUserIds);
        }

        public static CommentAdded of(Instant occurredAt, UUID workspaceId, UUID projectId, UUID commentId,
                UUID issueId, String issueKey, UUID authorId, String bodyText, List<UUID> mentionedUserIds) {
            return new CommentAdded(Ids.newId(), occurredAt, authorId, workspaceId, projectId, commentId, issueId,
                    issueKey, authorId, bodyText, mentionedUserIds, 0);
        }

        @Override
        public String type() {
            return EventTypes.COMMENT_ADDED;
        }
    }

    record CommentEdited(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
            UUID commentId, UUID issueId, String issueKey, UUID authorId, String bodyText, List<UUID> mentionedUserIds,
            long sequence) implements CommentEvent {

        public CommentEdited {
            mentionedUserIds = List.copyOf(mentionedUserIds);
        }

        /** {@code version}: the comment's version after the edit. */
        public static CommentEdited of(Instant occurredAt, UUID workspaceId, UUID projectId, UUID commentId,
                UUID issueId, String issueKey, UUID authorId, String bodyText, List<UUID> mentionedUserIds,
                long version) {
            return new CommentEdited(Ids.newId(), occurredAt, authorId, workspaceId, projectId, commentId, issueId,
                    issueKey, authorId, bodyText, mentionedUserIds, version);
        }

        @Override
        public String type() {
            return EventTypes.COMMENT_EDITED;
        }
    }

    record CommentDeleted(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
            UUID commentId, UUID issueId, String issueKey, UUID authorId, String bodyText, List<UUID> mentionedUserIds,
            long sequence) implements CommentEvent {

        public CommentDeleted {
            mentionedUserIds = List.copyOf(mentionedUserIds);
        }

        /** The actor may be a project admin removing someone else's comment; {@code authorId} stays the author. */
        public static CommentDeleted of(Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
                UUID commentId, UUID issueId, String issueKey, UUID authorId, long version) {
            return new CommentDeleted(Ids.newId(), occurredAt, actorId, workspaceId, projectId, commentId, issueId,
                    issueKey, authorId, "", List.of(), version);
        }

        @Override
        public String type() {
            return EventTypes.COMMENT_DELETED;
        }
    }
}

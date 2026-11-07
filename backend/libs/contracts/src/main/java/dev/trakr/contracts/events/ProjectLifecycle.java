package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A project's own events (04-events §4): created, updated, archived, member changed. The records are
 * nested ({@code ProjectLifecycle.Created}) so their names don't clash with the api's in-process
 * ProjectCreated, which the workflow and board modules listen to synchronously.
 */
public sealed interface ProjectLifecycle extends ProjectEvent {

    ProjectSnapshot snapshot();

    @Override
    default UUID workspaceId() {
        return snapshot().workspaceId();
    }

    @Override
    default UUID projectId() {
        return snapshot().id();
    }

    @Override
    default UUID aggregateId() {
        return snapshot().id();
    }

    @Override
    default String subject() {
        return "project/" + snapshot().id();
    }

    @Override
    default long sequence() {
        return snapshot().version();
    }

    record Created(UUID eventId, Instant occurredAt, UUID actorId, ProjectSnapshot snapshot) implements ProjectLifecycle {

        public static Created of(Instant occurredAt, UUID actorId, ProjectSnapshot snapshot) {
            return new Created(Ids.newId(), occurredAt, actorId, snapshot);
        }

        @Override
        public String type() {
            return EventTypes.PROJECT_CREATED;
        }
    }

    record Updated(UUID eventId, Instant occurredAt, UUID actorId, ProjectSnapshot snapshot) implements ProjectLifecycle {

        public static Updated of(Instant occurredAt, UUID actorId, ProjectSnapshot snapshot) {
            return new Updated(Ids.newId(), occurredAt, actorId, snapshot);
        }

        @Override
        public String type() {
            return EventTypes.PROJECT_UPDATED;
        }
    }

    record Archived(UUID eventId, Instant occurredAt, UUID actorId, ProjectSnapshot snapshot) implements ProjectLifecycle {

        public static Archived of(Instant occurredAt, UUID actorId, ProjectSnapshot snapshot) {
            return new Archived(Ids.newId(), occurredAt, actorId, snapshot);
        }

        @Override
        public String type() {
            return EventTypes.PROJECT_ARCHIVED;
        }
    }

    /** {@code role} null = removed from the project. */
    record MemberChanged(UUID eventId, Instant occurredAt, UUID actorId, ProjectSnapshot snapshot, UUID userId,
            @Nullable String role) implements ProjectLifecycle {

        public static MemberChanged of(Instant occurredAt, UUID actorId, ProjectSnapshot snapshot, UUID userId,
                @Nullable String role) {
            return new MemberChanged(Ids.newId(), occurredAt, actorId, snapshot, userId, role);
        }

        @Override
        public String type() {
            return EventTypes.PROJECT_MEMBER_CHANGED;
        }
    }
}

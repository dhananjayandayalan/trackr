package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;

/**
 * The sprint events (04-events §4, topic {@code trakr.projects.v1}): one record per type, all carrying
 * the sprint's snapshot. {@code deleted} is an addition to the catalogue — deleting a planned sprint
 * is a fact the board and backlog of other users need to hear about too.
 */
public sealed interface SprintEvent extends ProjectEvent {

    SprintSnapshot snapshot();

    @Override
    default UUID workspaceId() {
        return snapshot().workspaceId();
    }

    @Override
    default UUID projectId() {
        return snapshot().projectId();
    }

    @Override
    default UUID aggregateId() {
        return snapshot().id();
    }

    @Override
    default String subject() {
        return "sprint/" + snapshot().id();
    }

    @Override
    default long sequence() {
        return snapshot().version();
    }

    record SprintCreated(UUID eventId, Instant occurredAt, UUID actorId, SprintSnapshot snapshot) implements SprintEvent {

        public static SprintCreated of(Instant occurredAt, UUID actorId, SprintSnapshot snapshot) {
            return new SprintCreated(Ids.newId(), occurredAt, actorId, snapshot);
        }

        @Override
        public String type() {
            return EventTypes.SPRINT_CREATED;
        }
    }

    record SprintUpdated(UUID eventId, Instant occurredAt, UUID actorId, SprintSnapshot snapshot) implements SprintEvent {

        public static SprintUpdated of(Instant occurredAt, UUID actorId, SprintSnapshot snapshot) {
            return new SprintUpdated(Ids.newId(), occurredAt, actorId, snapshot);
        }

        @Override
        public String type() {
            return EventTypes.SPRINT_UPDATED;
        }
    }

    record SprintStarted(UUID eventId, Instant occurredAt, UUID actorId, SprintSnapshot snapshot) implements SprintEvent {

        public static SprintStarted of(Instant occurredAt, UUID actorId, SprintSnapshot snapshot) {
            return new SprintStarted(Ids.newId(), occurredAt, actorId, snapshot);
        }

        @Override
        public String type() {
            return EventTypes.SPRINT_STARTED;
        }
    }

    record SprintCompleted(UUID eventId, Instant occurredAt, UUID actorId, SprintSnapshot snapshot,
            SprintReport report) implements SprintEvent {

        public static SprintCompleted of(Instant occurredAt, UUID actorId, SprintSnapshot snapshot,
                SprintReport report) {
            return new SprintCompleted(Ids.newId(), occurredAt, actorId, snapshot, report);
        }

        @Override
        public String type() {
            return EventTypes.SPRINT_COMPLETED;
        }
    }

    record SprintDeleted(UUID eventId, Instant occurredAt, UUID actorId, SprintSnapshot snapshot) implements SprintEvent {

        public static SprintDeleted of(Instant occurredAt, UUID actorId, SprintSnapshot snapshot) {
            return new SprintDeleted(Ids.newId(), occurredAt, actorId, snapshot);
        }

        @Override
        public String type() {
            return EventTypes.SPRINT_DELETED;
        }
    }
}

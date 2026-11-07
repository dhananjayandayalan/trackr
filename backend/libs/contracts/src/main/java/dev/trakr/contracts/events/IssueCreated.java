package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;

/** {@code trakr.issue.created.v1}. Its sequence is 0: a new row starts at version 0. */
public record IssueCreated(UUID eventId, Instant occurredAt, UUID actorId, IssueSnapshot snapshot)
        implements IssueEvent {

    public static IssueCreated of(Instant occurredAt, UUID actorId, IssueSnapshot snapshot) {
        return new IssueCreated(Ids.newId(), occurredAt, actorId, snapshot);
    }

    @Override
    public String type() {
        return EventTypes.ISSUE_CREATED;
    }

    @Override
    public UUID workspaceId() {
        return snapshot.workspaceId();
    }

    @Override
    public UUID projectId() {
        return snapshot.projectId();
    }

    @Override
    public UUID aggregateId() {
        return snapshot.id();
    }

    @Override
    public long sequence() {
        return snapshot.version();
    }
}

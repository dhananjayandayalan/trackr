package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;

/** {@code trakr.issue.transitioned.v1}: the issue moved to another workflow status. */
public record IssueTransitioned(UUID eventId, Instant occurredAt, UUID actorId, UUID fromStatusId, UUID toStatusId,
        String toCategory, IssueSnapshot snapshot) implements TrakrEvent {

    public static IssueTransitioned of(Instant occurredAt, UUID actorId, UUID fromStatusId, IssueSnapshot snapshot) {
        return new IssueTransitioned(Ids.newId(), occurredAt, actorId, fromStatusId, snapshot.statusId(),
                snapshot.statusCategory(), snapshot);
    }

    @Override
    public String type() {
        return "trakr.issue.transitioned.v1";
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

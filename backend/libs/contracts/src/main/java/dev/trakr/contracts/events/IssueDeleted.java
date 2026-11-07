package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;

/** {@code trakr.issue.deleted.v1}: soft-deleted; one event per issue, so each deleted subtask gets its own. */
public record IssueDeleted(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
        UUID issueId, String key, long sequence) implements TrakrEvent {

    public static IssueDeleted of(Instant occurredAt, UUID actorId, IssueSnapshot snapshot) {
        return new IssueDeleted(Ids.newId(), occurredAt, actorId, snapshot.workspaceId(), snapshot.projectId(),
                snapshot.id(), snapshot.key(), snapshot.version());
    }

    @Override
    public String type() {
        return "trakr.issue.deleted.v1";
    }

    @Override
    public UUID aggregateId() {
        return issueId;
    }
}

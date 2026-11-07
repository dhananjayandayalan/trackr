package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;

/** {@code trakr.issue.work-logged.v1}: {@code userId} spent {@code minutes} on the issue. */
public record WorkLogged(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
        UUID issueId, long sequence, int minutes, UUID userId) implements TrakrEvent {

    public static WorkLogged of(Instant occurredAt, UUID actorId, IssueSnapshot snapshot, int minutes) {
        return new WorkLogged(Ids.newId(), occurredAt, actorId, snapshot.workspaceId(), snapshot.projectId(),
                snapshot.id(), snapshot.version(), minutes, actorId);
    }

    @Override
    public String type() {
        return "trakr.issue.work-logged.v1";
    }

    @Override
    public UUID aggregateId() {
        return issueId;
    }
}

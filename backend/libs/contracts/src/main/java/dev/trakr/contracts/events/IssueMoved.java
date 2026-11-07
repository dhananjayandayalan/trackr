package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code trakr.issue.moved.v1}: new rank, and possibly a new status or sprint ({@code sprintId} null =
 * backlog). A status change also publishes IssueTransitioned, so consumers that only care about
 * statuses need not read this one.
 */
public record IssueMoved(UUID eventId, Instant occurredAt, UUID actorId, UUID statusId, @Nullable UUID sprintId,
        String rank, @Nullable UUID previousSprintId, IssueSnapshot snapshot) implements TrakrEvent {

    public static IssueMoved of(Instant occurredAt, UUID actorId, @Nullable UUID previousSprintId,
            IssueSnapshot snapshot) {
        return new IssueMoved(Ids.newId(), occurredAt, actorId, snapshot.statusId(), snapshot.sprintId(),
                snapshot.rank(), previousSprintId, snapshot);
    }

    @Override
    public String type() {
        return "trakr.issue.moved.v1";
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

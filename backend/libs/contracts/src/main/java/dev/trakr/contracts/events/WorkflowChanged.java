package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code trakr.workflow.changed.v1} (04-events §4, phase 17): a project's workflow was replaced as a whole.
 * Data: the statuses and transitions as they are now, plus {@code remapped} — deleted status id → the status
 * its issues moved to. Consumers that show status names or categories (the search index, the burndown)
 * re-read the project's issues; the browsers refetch.
 *
 * <p>{@code sequence} is the workflow's own version, not the project's: they are separate counters, and the
 * subject ({@code workflow/…}) says which one it is.
 */
public record WorkflowChanged(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
        long version, List<Status> statuses, List<Transition> transitions, Map<UUID, UUID> remapped)
        implements ProjectEvent {

    public record Status(UUID id, String name, String category, int position, String color,
            @Nullable Integer wipLimit) {
    }

    /** {@code fromStatusId} null = from any status. */
    public record Transition(@Nullable UUID fromStatusId, UUID toStatusId) {
    }

    public WorkflowChanged {
        statuses = List.copyOf(statuses);
        transitions = List.copyOf(transitions);
        remapped = Map.copyOf(remapped);
    }

    public static WorkflowChanged of(Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId, long version,
            List<Status> statuses, List<Transition> transitions, Map<UUID, UUID> remapped) {
        return new WorkflowChanged(Ids.newId(), occurredAt, actorId, workspaceId, projectId, version, statuses,
                transitions, remapped);
    }

    @Override
    public String type() {
        return EventTypes.WORKFLOW_CHANGED;
    }

    @Override
    public UUID aggregateId() {
        return projectId;
    }

    @Override
    public String subject() {
        return "workflow/" + projectId;
    }

    @Override
    public long sequence() {
        return version;
    }
}

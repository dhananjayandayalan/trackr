package dev.trakr.api.workflow;

import dev.trakr.api.workflow.WorkflowView.TransitionView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * PUT /projects/{key}/workflow: the whole workflow as it should be.
 *
 * <p>{@code statuses} in their new order. An id the project already has is that status, edited; any other id
 * is a new status (the browser makes the id, so transitions can point at a status that doesn't exist yet).
 * A status of the project that isn't in the list is deleted; its issues move to {@code remapTo.get(id)}.
 */
public record WorkflowReplacement(List<StatusDraft> statuses, List<TransitionView> transitions,
        Map<UUID, UUID> remapTo) {

    public WorkflowReplacement {
        statuses = List.copyOf(statuses);
        transitions = List.copyOf(transitions);
        remapTo = Map.copyOf(remapTo);
    }

    /** {@code color} null = the category's default colour. */
    public record StatusDraft(UUID id, String name, String category, @Nullable String color,
            @Nullable Integer wipLimit) {
    }
}

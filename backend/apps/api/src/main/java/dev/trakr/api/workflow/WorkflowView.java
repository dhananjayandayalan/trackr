package dev.trakr.api.workflow;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * GET /projects/{key}/workflow (03-api §3.3): the statuses in order, the allowed transitions and the
 * version the workflow editor sends back in If-Match.
 */
public record WorkflowView(List<WorkflowStatusView> statuses, List<TransitionView> transitions, long version) {

    /** {@code fromStatusId} null = from any status. */
    public record TransitionView(@Nullable UUID fromStatusId, UUID toStatusId) {
    }
}

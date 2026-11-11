package dev.trakr.api.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-process, synchronous (like ProjectCreated): published inside the workflow PUT's transaction after the
 * statuses were written and before the deleted ones are removed. The issue module moves issues off the deleted
 * statuses and re-copies categories; the board module fixes its columns. If either fails, the whole save rolls
 * back — a workflow is never half-replaced.
 *
 * @param statuses        the statuses as they are now, in order
 * @param remapTo         deleted status id → the status its issues move to
 * @param added           statuses that are new in this save
 * @param deleted         statuses that are about to be removed
 * @param previousNames   old name of every status that was renamed (a column named after it follows)
 */
public record WorkflowReplaced(UUID projectId, List<WorkflowStatusView> statuses, Map<UUID, UUID> remapTo,
        Set<UUID> added,
        Set<UUID> deleted, Map<UUID, String> previousNames) {
}

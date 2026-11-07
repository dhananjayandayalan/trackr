package dev.trakr.api.workflow;

import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.api.workflow.internal.WorkflowStatus;
import dev.trakr.api.workflow.internal.WorkflowStatusRepository;
import dev.trakr.api.workflow.internal.WorkflowTransitionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workflow module's public API: a project's statuses, and which moves between them are allowed.
 * Issues ask here before every status change; the rules live with the workflow, not with the issue.
 */
@Service
public class WorkflowService {

    private final WorkflowStatusRepository statuses;
    private final WorkflowTransitionRepository transitions;

    WorkflowService(WorkflowStatusRepository statuses, WorkflowTransitionRepository transitions) {
        this.statuses = statuses;
        this.transitions = transitions;
    }

    // JPA on purpose, not jOOQ: the board seeder calls this in the transaction that just saved the statuses.
    // A JPQL query first flushes pending INSERTs of the entities it reads; a jOOQ query would see nothing.
    @Transactional(readOnly = true)
    public List<WorkflowStatusView> statusesOf(UUID projectId) {
        return statuses.findByProjectIdOrderByPosition(projectId).stream().map(WorkflowStatus::toView).toList();
    }

    /** A status of this project; 400 when the id belongs to no status here (another project's, or made up). */
    @Transactional(readOnly = true)
    public WorkflowStatusView status(UUID projectId, UUID statusId) {
        return statuses.findByIdAndProjectId(statusId, projectId).map(WorkflowStatus::toView)
                .orElseThrow(() -> new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "No such status in this project.", List.of(new FieldMessage("toStatusId", "is not a status of this project"))));
    }

    /** Where new issues start: the first {@code todo} status by position. */
    @Transactional(readOnly = true)
    public WorkflowStatusView initialStatus(UUID projectId) {
        return statuses.findFirstByProjectIdAndCategoryOrderByPosition(projectId, "todo").map(WorkflowStatus::toView)
                .orElseThrow(() -> new TrakrException.Conflict("NO_INITIAL_STATUS",
                        "The project's workflow has no status in the To do category."));
    }

    /**
     * The target status if the workflow allows {@code from → to}; else 409 TRANSITION_NOT_ALLOWED.
     * A transition with no from-status means "from any status".
     */
    @Transactional(readOnly = true)
    public WorkflowStatusView requireTransition(UUID projectId, UUID fromStatusId, UUID toStatusId) {
        WorkflowStatusView to = status(projectId, toStatusId);
        if (!transitions.isAllowed(projectId, fromStatusId, toStatusId)) {
            throw new TransitionNotAllowed(status(projectId, fromStatusId).name(), to.name());
        }
        return to;
    }

    /** The statuses an issue in {@code fromStatusId} may move to, in workflow order (GET /issues/{key}/transitions). */
    @Transactional(readOnly = true)
    public List<WorkflowStatusView> allowedTargets(UUID projectId, UUID fromStatusId) {
        return statuses.findTargets(projectId, fromStatusId).stream().map(WorkflowStatus::toView).toList();
    }
}

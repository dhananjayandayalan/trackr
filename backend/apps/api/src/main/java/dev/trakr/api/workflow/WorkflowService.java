package dev.trakr.api.workflow;

import dev.trakr.api.workflow.internal.WorkflowStatus;
import dev.trakr.api.workflow.internal.WorkflowStatusRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The workflow module's public API; grows with transitions and workflow editing in phase 07. */
@Service
public class WorkflowService {

    private final WorkflowStatusRepository statuses;

    WorkflowService(WorkflowStatusRepository statuses) {
        this.statuses = statuses;
    }

    // JPA on purpose, not jOOQ: the board seeder calls this in the transaction that just saved the statuses.
    // A JPQL query first flushes pending INSERTs of the entities it reads; a jOOQ query would see nothing.
    @Transactional(readOnly = true)
    public List<WorkflowStatusView> statusesOf(UUID projectId) {
        return statuses.findByProjectIdOrderByPosition(projectId).stream().map(WorkflowStatus::toView).toList();
    }
}

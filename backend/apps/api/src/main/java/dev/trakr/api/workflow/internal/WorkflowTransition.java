package dev.trakr.api.workflow.internal;

import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "workflow_transition")
public class WorkflowTransition {

    @Id
    private UUID id;
    private UUID projectId;
    // null = from any status
    private @Nullable UUID fromStatusId;
    private UUID toStatusId;

    protected WorkflowTransition() {
        // for JPA
    }

    public static WorkflowTransition between(UUID projectId, @Nullable UUID fromStatusId, UUID toStatusId) {
        WorkflowTransition transition = new WorkflowTransition();
        transition.id = Ids.newId();
        transition.projectId = projectId;
        transition.fromStatusId = fromStatusId;
        transition.toStatusId = toStatusId;
        return transition;
    }
}

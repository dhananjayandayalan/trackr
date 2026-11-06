package dev.trakr.api.workflow.internal;

import dev.trakr.api.workflow.WorkflowStatusView;
import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "workflow_status")
public class WorkflowStatus {

    @Id
    private UUID id;
    private UUID projectId;
    private String name;
    private String category;
    private int position;
    private String color;
    private @Nullable Integer wipLimit;

    protected WorkflowStatus() {
        // for JPA
    }

    public static WorkflowStatus create(UUID projectId, String name, String category, int position, String color,
            @Nullable Integer wipLimit) {
        WorkflowStatus status = new WorkflowStatus();
        status.id = Ids.newId();
        status.projectId = projectId;
        status.name = name;
        status.category = category;
        status.position = position;
        status.color = color;
        status.wipLimit = wipLimit;
        return status;
    }

    public UUID id() {
        return id;
    }

    public WorkflowStatusView toView() {
        return new WorkflowStatusView(id, name, category, position, color, wipLimit);
    }
}

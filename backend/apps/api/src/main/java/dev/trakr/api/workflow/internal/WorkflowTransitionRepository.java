package dev.trakr.api.workflow.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, UUID> {
}

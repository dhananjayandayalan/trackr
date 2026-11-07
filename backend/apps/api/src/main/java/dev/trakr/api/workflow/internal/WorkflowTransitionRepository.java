package dev.trakr.api.workflow.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, UUID> {

    // the unique constraint's index (project_id, from_status_id, to_status_id) answers this
    @Query("""
            select count(t) > 0 from WorkflowTransition t
            where t.projectId = :projectId and t.toStatusId = :to
              and (t.fromStatusId = :from or t.fromStatusId is null)""")
    boolean isAllowed(UUID projectId, UUID from, UUID to);
}

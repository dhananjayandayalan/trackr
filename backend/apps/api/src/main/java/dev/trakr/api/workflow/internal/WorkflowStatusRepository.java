package dev.trakr.api.workflow.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WorkflowStatusRepository extends JpaRepository<WorkflowStatus, UUID> {

    List<WorkflowStatus> findByProjectIdOrderByPosition(UUID projectId);

    Optional<WorkflowStatus> findByIdAndProjectId(UUID id, UUID projectId);

    // where a new issue starts: the first "todo" status in the workflow's order
    Optional<WorkflowStatus> findFirstByProjectIdAndCategoryOrderByPosition(UUID projectId, String category);

    // the statuses an issue in status :from may move to: a transition from :from, or from any status (null)
    @Query("""
            select s from WorkflowStatus s
            where s.projectId = :projectId and s.id <> :from
              and exists (select t from WorkflowTransition t
                          where t.projectId = :projectId and t.toStatusId = s.id
                            and (t.fromStatusId = :from or t.fromStatusId is null))
            order by s.position""")
    List<WorkflowStatus> findTargets(UUID projectId, UUID from);
}

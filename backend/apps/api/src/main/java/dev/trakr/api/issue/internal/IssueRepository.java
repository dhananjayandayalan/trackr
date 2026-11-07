package dev.trakr.api.issue.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IssueRepository extends JpaRepository<Issue, UUID> {

    // uq_issue_workspace_id_key: one index hit
    Optional<Issue> findByWorkspaceIdAndKeyAndDeletedAtIsNull(UUID workspaceId, String key);

    List<Issue> findByParentIdAndDeletedAtIsNull(UUID parentId);

    // a sprint's issues in board order; rank is COLLATE "C", so ORDER BY compares bytes like Rank does
    List<Issue> findBySprintIdAndDeletedAtIsNullOrderByRank(UUID sprintId);

    // a planned sprint is being deleted: soft-deleted issues still point at it, and the foreign key would refuse.
    // A bulk UPDATE skips the version: nobody can edit a deleted issue, so there is no conflict to detect.
    @Modifying
    @Query("update Issue i set i.sprintId = null where i.sprintId = :sprintId and i.deletedAt is not null")
    int detachDeletedFromSprint(UUID sprintId);

    @Query("select distinct i.type from Issue i where i.parentId = :parentId and i.deletedAt is null")
    List<String> childTypes(UUID parentId);
}

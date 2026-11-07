package dev.trakr.api.issue.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IssueRepository extends JpaRepository<Issue, UUID> {

    // uq_issue_workspace_id_key: one index hit
    Optional<Issue> findByWorkspaceIdAndKeyAndDeletedAtIsNull(UUID workspaceId, String key);

    List<Issue> findByParentIdAndDeletedAtIsNull(UUID parentId);

    @Query("select distinct i.type from Issue i where i.parentId = :parentId and i.deletedAt is null")
    List<String> childTypes(UUID parentId);
}

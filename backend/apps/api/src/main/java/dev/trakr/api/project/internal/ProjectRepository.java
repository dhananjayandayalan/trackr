package dev.trakr.api.project.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByWorkspaceIdAndKey(UUID workspaceId, String key);

    boolean existsByWorkspaceIdAndKey(UUID workspaceId, String key);
}

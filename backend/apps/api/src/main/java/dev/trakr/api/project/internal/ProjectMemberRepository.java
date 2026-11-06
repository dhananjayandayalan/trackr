package dev.trakr.api.project.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMember.Key> {

    // "cannot remove the last admin" (03-api §3.2)
    long countByProjectIdAndRole(UUID projectId, String role);
}

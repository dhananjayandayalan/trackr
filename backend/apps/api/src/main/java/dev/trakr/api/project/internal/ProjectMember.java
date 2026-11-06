package dev.trakr.api.project.internal;

import dev.trakr.api.project.ProjectRole;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_member")
@IdClass(ProjectMember.Key.class)
public class ProjectMember {

    public record Key(UUID projectId, UUID userId) implements Serializable {
    }

    @Id
    private UUID projectId;
    @Id
    private UUID userId;
    private String role;
    private Instant addedAt;

    protected ProjectMember() {
        // for JPA
    }

    public static ProjectMember add(UUID projectId, UUID userId, ProjectRole role, Clock clock) {
        ProjectMember member = new ProjectMember();
        member.projectId = projectId;
        member.userId = userId;
        member.role = role.value();
        member.addedAt = clock.instant();
        return member;
    }

    public void changeRole(ProjectRole newRole) {
        role = newRole.value();
    }

    public UUID userId() {
        return userId;
    }

    public ProjectRole role() {
        return ProjectRole.fromValue(role);
    }
}

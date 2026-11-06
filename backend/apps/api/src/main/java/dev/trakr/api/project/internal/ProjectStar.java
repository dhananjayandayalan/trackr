package dev.trakr.api.project.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_star")
@IdClass(ProjectStar.Key.class)
public class ProjectStar {

    public record Key(UUID userId, UUID projectId) implements Serializable {
    }

    @Id
    private UUID userId;
    @Id
    private UUID projectId;
    private Instant starredAt;

    protected ProjectStar() {
        // for JPA
    }

    public static ProjectStar of(UUID userId, UUID projectId, Clock clock) {
        ProjectStar star = new ProjectStar();
        star.userId = userId;
        star.projectId = projectId;
        star.starredAt = clock.instant();
        return star;
    }
}

package dev.trakr.api.project.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** "I opened this project at…", one row per user and project; powers "Recent projects". */
@Entity
@Table(name = "project_visit")
@IdClass(ProjectVisit.Key.class)
public class ProjectVisit {

    public record Key(UUID userId, UUID projectId) implements Serializable {
    }

    @Id
    private UUID userId;
    @Id
    private UUID projectId;
    private Instant visitedAt;

    protected ProjectVisit() {
        // for JPA
    }

    // save() of an entity with a known id is a merge: INSERT the first time, UPDATE visited_at after that
    public static ProjectVisit now(UUID userId, UUID projectId, Clock clock) {
        ProjectVisit visit = new ProjectVisit();
        visit.userId = userId;
        visit.projectId = projectId;
        visit.visitedAt = clock.instant();
        return visit;
    }
}

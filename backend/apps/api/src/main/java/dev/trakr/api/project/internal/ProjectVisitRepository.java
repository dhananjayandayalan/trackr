package dev.trakr.api.project.internal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectVisitRepository extends JpaRepository<ProjectVisit, ProjectVisit.Key> {
}

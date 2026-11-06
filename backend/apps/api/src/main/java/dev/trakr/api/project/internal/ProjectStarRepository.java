package dev.trakr.api.project.internal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectStarRepository extends JpaRepository<ProjectStar, ProjectStar.Key> {
}

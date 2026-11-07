package dev.trakr.api.issue.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorklogRepository extends JpaRepository<Worklog, UUID> {
}

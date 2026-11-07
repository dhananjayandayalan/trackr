package dev.trakr.api.issue.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueLinkRepository extends JpaRepository<IssueLink, UUID> {
}

package dev.trakr.api.issue.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueEventRepository extends JpaRepository<IssueEventRecord, Long> {

    // a derived query: Spring Data writes the SQL from the method name (WHERE issue_id = ? AND actor_id = ? …)
    boolean existsByIssueIdAndActorIdAndKindAndFieldAndOccurredAtAfter(UUID issueId, UUID actorId, String kind,
            String field, Instant after);
}

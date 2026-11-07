package dev.trakr.api.issue.internal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueEventRepository extends JpaRepository<IssueEventRecord, Long> {
}

package dev.trakr.api.sprint.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SprintRepository extends JpaRepository<Sprint, UUID> {

    // SELECT … FOR UPDATE (or FOR NO KEY UPDATE, depending on the Hibernate dialect): completing the same
    // sprint twice at once must not move its issues twice — the second caller waits, then sees it closed
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sprint s where s.id = :id")
    Optional<Sprint> findLockedById(UUID id);

    boolean existsByProjectIdAndState(UUID projectId, String state);

    long countByProjectId(UUID projectId);

    @Query("select coalesce(max(s.position), -1) from Sprint s where s.projectId = :projectId")
    int maxPosition(UUID projectId);
}

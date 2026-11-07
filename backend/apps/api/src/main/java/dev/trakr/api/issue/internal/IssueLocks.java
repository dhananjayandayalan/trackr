package dev.trakr.api.issue.internal;

import static dev.trakr.jooq.Tables.ISSUE;
import static dev.trakr.jooq.Tables.PROJECT;

import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pessimistic row locks, held until the surrounding transaction ends (hence MANDATORY).
 *
 * <p>Why not only {@code @Version}? Optimistic locking protects one row against a lost update. A rank
 * is computed from <em>other</em> rows (the neighbours): two moves into the same gap read the same
 * neighbours, both pass their own version check, and both write the same rank. Nothing conflicts at
 * the row level, so only a lock that both must take can serialise them.
 *
 * <p>Why {@code FOR NO KEY UPDATE} and not {@code FOR UPDATE}? Inserting a row with a foreign key
 * takes a {@code FOR KEY SHARE} lock on the row it points at (so that row can't be deleted
 * meanwhile). {@code FOR UPDATE} blocks that; {@code FOR NO KEY UPDATE} doesn't — we change ranks and
 * statuses, never the key. So history rows, watchers and new issues can still be inserted while a
 * move holds the lock.
 */
@Component
public class IssueLocks {

    private final DSLContext dsl;

    IssueLocks(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Everything that computes a rank in this project — create (whose number allocation takes the same
     * lock), move, sprint completion — queues here, and reads the neighbours only after getting it. A
     * move is a few milliseconds of work, so the queue is short.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockRanks(UUID projectId) {
        dsl.select(PROJECT.ID).from(PROJECT).where(PROJECT.ID.eq(projectId)).forNoKeyUpdate().fetch();
    }

    /** Locks one live issue for a move; empty when there is no such issue. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> lockIssue(UUID workspaceId, String key) {
        return dsl.select(ISSUE.ID).from(ISSUE)
                .where(ISSUE.WORKSPACE_ID.eq(workspaceId), ISSUE.KEY.eq(key), ISSUE.DELETED_AT.isNull())
                .forNoKeyUpdate()
                .fetchOptional(ISSUE.ID);
    }
}

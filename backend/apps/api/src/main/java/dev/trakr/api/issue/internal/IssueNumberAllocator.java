package dev.trakr.api.issue.internal;

import static dev.trakr.jooq.Tables.PROJECT;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The next issue number of a project (MAR-131 → 131), from project.issue_seq.
 *
 * <p>{@code UPDATE … SET issue_seq = issue_seq + 1 … RETURNING issue_seq} reads and increments in one
 * statement, and the UPDATE locks the project row until the surrounding transaction ends. A second
 * transaction creating an issue in the same project waits at its UPDATE until the first commits, then
 * sees the incremented value: no two issues get the same number, and a rollback gives the number back
 * (a rolled-back UPDATE never happened), so there are no gaps either. The cost: creates in one
 * project run one at a time for the rest of their transaction. Issue creation is a human action, a
 * few per second at most per project, so that queue is always short.
 */
@Component
public class IssueNumberAllocator {

    private final DSLContext dsl;

    IssueNumberAllocator(DSLContext dsl) {
        this.dsl = dsl;
    }

    // MANDATORY: called outside a transaction the lock would end at once, before the issue INSERT — fail instead
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(UUID projectId) {
        return dsl.update(PROJECT)
                .set(PROJECT.ISSUE_SEQ, PROJECT.ISSUE_SEQ.plus(1L))
                .where(PROJECT.ID.eq(projectId))
                .returningResult(PROJECT.ISSUE_SEQ)
                .fetchSingle()
                .value1();
    }
}

package dev.trakr.api.workflow.internal;

import static dev.trakr.jooq.Tables.ISSUE;
import static dev.trakr.jooq.Tables.WORKFLOW;
import static dev.trakr.jooq.Tables.WORKFLOW_STATUS;
import static dev.trakr.jooq.Tables.WORKFLOW_TRANSITION;

import dev.trakr.api.workflow.WorkflowStatusView;
import dev.trakr.api.workflow.WorkflowView.TransitionView;
import dev.trakr.kernel.Ids;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * The workflow editor's reads and writes, in jOOQ: a save rewrites many rows with a few set-based statements,
 * where JPA would load and dirty-check every entity. The seeder and the transition checks keep using JPA; they
 * never run in the same transaction as a save.
 */
@Component
public class WorkflowStore {

    private final DSLContext dsl;

    WorkflowStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    // BLOCK 1: reads
    public List<WorkflowStatusView> statuses(UUID projectId) {
        return dsl.select(WORKFLOW_STATUS.ID, WORKFLOW_STATUS.NAME, WORKFLOW_STATUS.CATEGORY, WORKFLOW_STATUS.POSITION,
                        WORKFLOW_STATUS.COLOR, WORKFLOW_STATUS.WIP_LIMIT)
                .from(WORKFLOW_STATUS)
                .where(WORKFLOW_STATUS.PROJECT_ID.eq(projectId))
                .orderBy(WORKFLOW_STATUS.POSITION)
                .fetch(Records.mapping(WorkflowStatusView::new));
    }

    public List<TransitionView> transitions(UUID projectId) {
        return dsl.select(WORKFLOW_TRANSITION.FROM_STATUS_ID, WORKFLOW_TRANSITION.TO_STATUS_ID)
                .from(WORKFLOW_TRANSITION)
                .where(WORKFLOW_TRANSITION.PROJECT_ID.eq(projectId))
                // "from any status" first, then by the statuses' order: a stable order for the editor's matrix
                .orderBy(WORKFLOW_TRANSITION.FROM_STATUS_ID.nullsFirst(), WORKFLOW_TRANSITION.TO_STATUS_ID)
                .fetch(Records.mapping(TransitionView::new));
    }

    /** No row = version 0: projects created before phase 17 get their row on the first save. */
    public long version(UUID projectId) {
        return dsl.select(WORKFLOW.VERSION).from(WORKFLOW).where(WORKFLOW.PROJECT_ID.eq(projectId))
                .fetchOptional(WORKFLOW.VERSION).orElse(0L);
    }

    /** Ids among these that are statuses of some other project (a client can't reuse those for new statuses). */
    public Set<UUID> takenElsewhere(UUID projectId, Collection<UUID> ids) {
        return dsl.select(WORKFLOW_STATUS.ID).from(WORKFLOW_STATUS)
                .where(WORKFLOW_STATUS.ID.in(ids), WORKFLOW_STATUS.PROJECT_ID.ne(projectId))
                .fetchSet(WORKFLOW_STATUS.ID);
    }

    /** Issues (soft-deleted ones too: their row still points at the status) per status, for these statuses. */
    public Map<UUID, Integer> issueCounts(Collection<UUID> statusIds) {
        return dsl.select(ISSUE.STATUS_ID, DSL.count())
                .from(ISSUE)
                .where(ISSUE.STATUS_ID.in(statusIds))
                .groupBy(ISSUE.STATUS_ID)
                .fetchMap(ISSUE.STATUS_ID, DSL.count());
    }

    // BLOCK 2: the version — the optimistic lock, taken first
    /**
     * {@code version = version + 1} if it is still {@code expected}; false when someone saved in between. The
     * upsert's row lock makes a second concurrent save wait here until the first one commits, then fail.
     */
    public boolean bumpVersion(UUID projectId, long expected) {
        return dsl.insertInto(WORKFLOW)
                .set(WORKFLOW.PROJECT_ID, projectId)
                .set(WORKFLOW.VERSION, expected + 1)
                .onConflict(WORKFLOW.PROJECT_ID)
                .doUpdate()
                .set(WORKFLOW.VERSION, WORKFLOW.VERSION.plus(1))
                .where(WORKFLOW.VERSION.eq(expected))
                .execute() == 1;
    }

    // BLOCK 3: writes, in the order WorkflowService calls them
    public void deleteTransitions(UUID projectId) {
        dsl.deleteFrom(WORKFLOW_TRANSITION).where(WORKFLOW_TRANSITION.PROJECT_ID.eq(projectId)).execute();
    }

    /**
     * Every status gets a placeholder name first, so the real names can then be written in any order: a swap
     * (A→B, B→A) would otherwise break the unique (project, name) constraint halfway.
     */
    public void parkNames(UUID projectId) {
        dsl.update(WORKFLOW_STATUS)
                .set(WORKFLOW_STATUS.NAME, DSL.concat(DSL.inline("~"),
                        DSL.substring(WORKFLOW_STATUS.ID.cast(String.class), DSL.inline(25))))
                .where(WORKFLOW_STATUS.PROJECT_ID.eq(projectId))
                .execute();
    }

    public void update(WorkflowStatusView status) {
        dsl.update(WORKFLOW_STATUS)
                .set(WORKFLOW_STATUS.NAME, status.name())
                .set(WORKFLOW_STATUS.CATEGORY, status.category())
                .set(WORKFLOW_STATUS.POSITION, status.position())
                .set(WORKFLOW_STATUS.COLOR, status.color())
                .set(WORKFLOW_STATUS.WIP_LIMIT, status.wipLimit())
                .where(WORKFLOW_STATUS.ID.eq(status.id()))
                .execute();
    }

    public void insert(UUID projectId, WorkflowStatusView status) {
        dsl.insertInto(WORKFLOW_STATUS)
                .set(WORKFLOW_STATUS.ID, status.id())
                .set(WORKFLOW_STATUS.PROJECT_ID, projectId)
                .set(WORKFLOW_STATUS.NAME, status.name())
                .set(WORKFLOW_STATUS.CATEGORY, status.category())
                .set(WORKFLOW_STATUS.POSITION, status.position())
                .set(WORKFLOW_STATUS.COLOR, status.color())
                .set(WORKFLOW_STATUS.WIP_LIMIT, status.wipLimit())
                .execute();
    }

    public void deleteStatuses(Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            dsl.deleteFrom(WORKFLOW_STATUS).where(WORKFLOW_STATUS.ID.in(ids)).execute();
        }
    }

    public void insertTransitions(UUID projectId, List<TransitionView> transitions) {
        if (transitions.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(WORKFLOW_TRANSITION, WORKFLOW_TRANSITION.ID, WORKFLOW_TRANSITION.PROJECT_ID,
                WORKFLOW_TRANSITION.FROM_STATUS_ID, WORKFLOW_TRANSITION.TO_STATUS_ID);
        for (TransitionView transition : transitions) {
            insert = insert.values(Ids.newId(), projectId, transition.fromStatusId(), transition.toStatusId());
        }
        insert.execute();
    }
}

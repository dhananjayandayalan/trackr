package dev.trakr.api.issue.internal;

import static dev.trakr.jooq.Tables.ISSUE;

import dev.trakr.api.workflow.WorkflowReplaced;
import dev.trakr.api.workflow.WorkflowStatusView;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps issues valid when the workflow editor saves (phase 17). Synchronous, inside the save's transaction:
 * it runs after the new statuses are written and before the deleted ones are removed, so the foreign key
 * {@code issue.status_id → workflow_status} never points at nothing.
 *
 * <p>Two set-based UPDATEs instead of loading every issue: an issue's status and its denormalised
 * {@code status_category} are what change, and both are columns this module owns. Each touched issue's version
 * goes up — someone holding the old version gets a 412 instead of writing back the old status. No issue events:
 * the one {@code workflow.changed} event tells the search index and the browsers to re-read the project.
 */
@Component
class WorkflowRemapper {

    private final DSLContext dsl;

    WorkflowRemapper(DSLContext dsl) {
        this.dsl = dsl;
    }

    @EventListener
    void on(WorkflowReplaced event) {
        Map<UUID, WorkflowStatusView> byId = new java.util.HashMap<>();
        event.statuses().forEach(status -> byId.put(status.id(), status));

        // BLOCK 1: issues on a deleted status move to the status chosen for them
        event.remapTo().forEach((from, to) -> {
            String category = byId.get(to).category();
            dsl.update(ISSUE)
                    .set(ISSUE.STATUS_ID, to)
                    .set(ISSUE.STATUS_CATEGORY, category)
                    .set(ISSUE.RESOLVED_AT, resolvedAt(category))
                    .set(ISSUE.VERSION, ISSUE.VERSION.plus(1))
                    .set(ISSUE.UPDATED_AT, DSL.currentOffsetDateTime())
                    .where(ISSUE.STATUS_ID.eq(from))
                    .execute();
        });

        // BLOCK 2: a status that changed category takes its issues along (00-product §4.3: done sets resolved_at)
        for (WorkflowStatusView status : event.statuses()) {
            dsl.update(ISSUE)
                    .set(ISSUE.STATUS_CATEGORY, status.category())
                    .set(ISSUE.RESOLVED_AT, resolvedAt(status.category()))
                    .set(ISSUE.VERSION, ISSUE.VERSION.plus(1))
                    .set(ISSUE.UPDATED_AT, DSL.currentOffsetDateTime())
                    .where(ISSUE.STATUS_ID.eq(status.id()), ISSUE.STATUS_CATEGORY.ne(status.category()))
                    .execute();
        }
    }

    // into done: keep an existing resolution time, else now; out of done: cleared
    private static Field<java.time.OffsetDateTime> resolvedAt(String category) {
        return "done".equals(category)
                ? DSL.coalesce(ISSUE.RESOLVED_AT, DSL.currentOffsetDateTime())
                : DSL.castNull(ISSUE.RESOLVED_AT);
    }
}

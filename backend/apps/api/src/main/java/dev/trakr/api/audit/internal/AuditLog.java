package dev.trakr.api.audit.internal;

import static dev.trakr.jooq.Tables.AUDIT_LOG;

import dev.trakr.api.project.ProjectMemberChanged;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.workflow.WorkflowReplaced;
import dev.trakr.api.workflow.WorkflowStatusView;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSON;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends to audit_log. A plain {@code @EventListener}, not Modulith's async {@code @ApplicationModuleListener}:
 * it runs inside the transaction that made the change, so a change without its audit row (or the other way
 * round) can't happen — if the insert fails, the change rolls back with it.
 */
@Component
class AuditLog {

    private final DSLContext dsl;
    private final JsonMapper json;
    private final Clock clock;

    AuditLog(DSLContext dsl, JsonMapper json, Clock clock) {
        this.dsl = dsl;
        this.json = json;
        this.clock = clock;
    }

    // BLOCK 2: membership — added (no previous role), role changed, removed (no role now)
    @EventListener
    void on(ProjectMemberChanged event) {
        String action = event.previousRole() == null ? "project.member.added"
                : event.role() == null ? "project.member.removed" : "project.member.changed";
        append(event.workspaceId(), event.actorId(), action, "project:" + event.projectId(),
                member(event.userId(), event.previousRole()), member(event.userId(), event.role()));
    }

    // BLOCK 3: workflow — the statuses before and after, and where the deleted statuses' issues went
    @EventListener
    void on(WorkflowReplaced event) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("statuses", statuses(event.statuses()));
        if (!event.remapTo().isEmpty()) {
            after.put("remapped", event.remapTo());
        }
        append(event.workspaceId(), event.actorId(), "project.workflow.replaced", "project:" + event.projectId(),
                Map.of("statuses", statuses(event.previous())), after);
    }

    private static @Nullable Map<String, Object> member(UUID userId, @Nullable ProjectRole role) {
        return role == null ? null : Map.of("userId", userId, "role", role.value());
    }

    private static List<Map<String, Object>> statuses(List<WorkflowStatusView> statuses) {
        return statuses.stream()
                .map(status -> Map.<String, Object>of("id", status.id(), "name", status.name(),
                        "category", status.category()))
                .toList();
    }

    private void append(UUID workspaceId, UUID actorId, String action, String target,
            @Nullable Map<String, Object> before, @Nullable Map<String, Object> after) {
        dsl.insertInto(AUDIT_LOG)
                .set(AUDIT_LOG.WORKSPACE_ID, workspaceId)
                .set(AUDIT_LOG.ACTOR_ID, actorId)
                .set(AUDIT_LOG.ACTION, action)
                .set(AUDIT_LOG.TARGET, target)
                .set(AUDIT_LOG.BEFORE, jsonb(before))
                .set(AUDIT_LOG.AFTER, jsonb(after))
                .set(AUDIT_LOG.AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .execute();
    }

    // jOOQ generates the columns from H2's view of the migration, where jsonb is JSON; Postgres casts json to jsonb
    private @Nullable JSON jsonb(@Nullable Map<String, Object> value) {
        return value == null ? null : JSON.valueOf(json.writeValueAsString(value));
    }
}

package dev.trakr.api.workflow.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.shared.web.ETags;
import dev.trakr.api.workflow.WorkflowReplacement;
import dev.trakr.api.workflow.WorkflowReplacement.StatusDraft;
import dev.trakr.api.workflow.WorkflowService;
import dev.trakr.api.workflow.WorkflowView;
import dev.trakr.api.workflow.WorkflowView.TransitionView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 03-api §3.3: the workflow as one resource. GET hands out the version as the ETag; PUT replaces the whole
 * workflow and needs that ETag back in If-Match — two admins editing at once can't silently undo each other.
 */
@RestController
@RequestMapping("/api/v1/projects/{key}/workflow")
class WorkflowController {

    private final WorkflowService workflows;

    WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    record StatusRequest(@NotNull UUID id, @NotNull String name, @NotNull String category, @Nullable String color,
            @Nullable Integer wipLimit) {
    }

    /** {@code remapTo}: deleted status id → the status its issues move to. */
    record ReplaceWorkflowRequest(@NotNull List<StatusRequest> statuses, @NotNull List<TransitionView> transitions,
            @Nullable Map<UUID, UUID> remapTo) {
    }

    @Operation(operationId = "getWorkflow")
    @GetMapping
    ResponseEntity<WorkflowView> get(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        WorkflowView view = workflows.view(principal.id(), key);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @Operation(operationId = "replaceWorkflow")
    @PutMapping
    ResponseEntity<WorkflowView> replace(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch,
            @Valid @RequestBody ReplaceWorkflowRequest body) {
        WorkflowReplacement replacement = new WorkflowReplacement(
                body.statuses().stream().map(s -> new StatusDraft(s.id(), s.name(), s.category(), s.color(),
                        s.wipLimit())).toList(),
                body.transitions(), body.remapTo() == null ? Map.of() : body.remapTo());
        WorkflowView view = workflows.replace(principal.id(), key, ETags.versionFrom(ifMatch), replacement);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }
}

package dev.trakr.api.issue.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.issue.BulkCommand;
import dev.trakr.api.issue.HistoryItem;
import dev.trakr.api.issue.IssueDetails;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.MoveResult;
import dev.trakr.api.issue.TransitionIssue;
import dev.trakr.api.issue.web.IssueRequests.BulkRequest;
import dev.trakr.api.issue.web.IssueRequests.TransitionRequest;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.ETags;
import dev.trakr.api.workflow.WorkflowStatusView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 03-api §3.4. Issues are addressed by key (MAR-131). Every Issue response carries its ETag. */
@RestController
@RequestMapping("/api/v1/issues")
class IssueController {

    private final IssueService issues;

    IssueController(IssueService issues) {
        this.issues = issues;
    }

    // safe to retry with an Idempotency-Key header (IdempotencyFilter)
    @Operation(operationId = "createIssue")
    @PostMapping
    ResponseEntity<IssueDetails> create(@AuthenticationPrincipal TrakrPrincipal principal,
            @Valid @RequestBody CreateIssueRequest body) {
        IssueDetails issue = issues.create(principal.id(), body.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/issues/" + issue.key()))
                .eTag(ETags.of(issue.version()))
                .body(issue);
    }

    @Operation(operationId = "getIssue")
    @GetMapping("/{key}")
    ResponseEntity<IssueDetails> get(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        return withETag(issues.get(principal.id(), key));
    }

    @Operation(operationId = "updateIssue", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(mediaType = "application/merge-patch+json",
                    schema = @Schema(implementation = IssuePatchRequest.class))))
    @PatchMapping(path = "/{key}", consumes = {"application/merge-patch+json", "application/json"})
    ResponseEntity<IssueDetails> update(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch,
            @RequestBody Map<String, @Nullable Object> body) {
        // If-Match first: a request without it is refused whatever its body says
        long expectedVersion = ETags.versionFrom(ifMatch);
        return withETag(issues.patch(principal.id(), key, expectedVersion, IssuePatchRequest.parse(body)));
    }

    @Operation(operationId = "deleteIssue")
    @DeleteMapping("/{key}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        issues.delete(principal.id(), key, ETags.versionFrom(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "transitionIssue")
    @PostMapping("/{key}/transition")
    ResponseEntity<IssueDetails> transition(@AuthenticationPrincipal TrakrPrincipal principal,
            @PathVariable String key, @Valid @RequestBody TransitionRequest body) {
        return withETag(issues.transition(principal.id(), key,
                new TransitionIssue(body.toStatusId(), body.expectedVersion())));
    }

    /** Drag and drop on the board or in the backlog; the response may carry warnings (WIP limit, stale neighbours). */
    @Operation(operationId = "moveIssue", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = MoveRequest.class))))
    @PostMapping("/{key}/move")
    ResponseEntity<MoveResult> move(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestBody Map<String, @Nullable Object> body) {
        MoveResult result = issues.move(principal.id(), key, MoveRequest.parse(body));
        return ResponseEntity.ok().eTag(ETags.of(result.issue().version())).body(result);
    }

    @Operation(operationId = "bulkIssues")
    @PostMapping("/bulk")
    List<BulkCommand.Result> bulk(@AuthenticationPrincipal TrakrPrincipal principal,
            @Valid @RequestBody BulkRequest body) {
        return issues.bulk(principal.id(), body.toCommand());
    }

    @Operation(operationId = "listIssueTransitions")
    @GetMapping("/{key}/transitions")
    List<WorkflowStatusView> transitions(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        return issues.transitions(principal.id(), key);
    }

    @Operation(operationId = "getIssueHistory")
    @GetMapping("/{key}/history")
    CursorPage<HistoryItem> history(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestParam(required = false) @Nullable String cursor) {
        return issues.history(principal.id(), key, cursor);
    }

    private static ResponseEntity<IssueDetails> withETag(IssueDetails issue) {
        return ResponseEntity.ok().eTag(ETags.of(issue.version())).body(issue);
    }
}

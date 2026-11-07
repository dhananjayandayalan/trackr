package dev.trakr.api.issue.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.issue.IssueDetails.LinkView;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.web.IssueRequests.LinkRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Links are created under their source issue and deleted by id: a link has no key of its own. */
@RestController
class IssueLinkController {

    private final IssueService issues;

    IssueLinkController(IssueService issues) {
        this.issues = issues;
    }

    @Operation(operationId = "linkIssue")
    @PostMapping("/api/v1/issues/{key}/links")
    ResponseEntity<LinkView> link(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @Valid @RequestBody LinkRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issues.link(principal.id(), key, body.targetKey(), body.type()));
    }

    @Operation(operationId = "unlinkIssue")
    @DeleteMapping("/api/v1/issue-links/{id}")
    ResponseEntity<Void> unlink(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id) {
        issues.unlink(principal.id(), id);
        return ResponseEntity.noContent().build();
    }
}

package dev.trakr.api.issue.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.issue.IssueService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Watch and star: PUT to switch on, DELETE to switch off. Both are idempotent, so repeating either is harmless. */
@RestController
@RequestMapping("/api/v1/issues/{key}")
class WatchController {

    private final IssueService issues;

    WatchController(IssueService issues) {
        this.issues = issues;
    }

    @Operation(operationId = "watchIssue")
    @PutMapping("/watch")
    ResponseEntity<Void> watch(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        issues.watch(principal.id(), key);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "unwatchIssue")
    @DeleteMapping("/watch")
    ResponseEntity<Void> unwatch(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        issues.unwatch(principal.id(), key);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "starIssue")
    @PutMapping("/star")
    ResponseEntity<Void> star(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        issues.star(principal.id(), key);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "unstarIssue")
    @DeleteMapping("/star")
    ResponseEntity<Void> unstar(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        issues.unstar(principal.id(), key);
        return ResponseEntity.noContent().build();
    }
}

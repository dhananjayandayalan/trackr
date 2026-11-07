package dev.trakr.api.issue.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.NewWorklog;
import dev.trakr.api.issue.WorklogView;
import dev.trakr.api.issue.web.IssueRequests.WorklogRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/issues/{key}/worklogs")
class WorklogController {

    private final IssueService issues;

    WorklogController(IssueService issues) {
        this.issues = issues;
    }

    @Operation(operationId = "listWorklogs")
    @GetMapping
    List<WorklogView> list(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        return issues.worklogs(principal.id(), key);
    }

    @Operation(operationId = "logWork")
    @PostMapping
    ResponseEntity<WorklogView> log(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @Valid @RequestBody WorklogRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(issues.logWork(principal.id(), key,
                new NewWorklog(body.startedAt(), body.minutes(), body.note())));
    }
}

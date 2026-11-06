package dev.trakr.api.project.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.project.KeySuggestion;
import dev.trakr.api.project.ProjectDetails;
import dev.trakr.api.project.ProjectFilter;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.ProjectSummary;
import dev.trakr.api.project.ProjectView;
import dev.trakr.api.project.Template;
import dev.trakr.api.shared.web.ETags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 03-api §3.2. No logic here: unpack the request, call ProjectService, set status and headers. */
@RestController
@RequestMapping("/api/v1/projects")
class ProjectController {

    private final ProjectService projects;

    ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @Operation(operationId = "listProjects")
    @GetMapping
    List<ProjectSummary> list(@AuthenticationPrincipal TrakrPrincipal principal,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable Template template,
            @RequestParam(required = false) @Nullable UUID leadId,
            @RequestParam(defaultValue = "false") boolean starred) {
        return projects.list(principal.id(), new ProjectFilter(q, template, leadId, starred));
    }

    // safe to retry with an Idempotency-Key header (IdempotencyFilter)
    @Operation(operationId = "createProject")
    @PostMapping
    ResponseEntity<ProjectView> create(@AuthenticationPrincipal TrakrPrincipal principal,
            @Valid @RequestBody CreateProjectRequest body) {
        ProjectView project = projects.create(principal.id(), body.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + project.key()))
                .eTag(ETags.of(project.version()))
                .body(project);
    }

    @Operation(operationId = "suggestProjectKey")
    @GetMapping("/key-suggestion")
    KeySuggestion suggestKey(@AuthenticationPrincipal TrakrPrincipal principal, @RequestParam String name) {
        return projects.suggestKey(principal.id(), name);
    }

    @Operation(operationId = "getProject")
    @GetMapping("/{key}")
    ResponseEntity<ProjectDetails> get(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        ProjectDetails details = projects.open(principal.id(), key);
        return ResponseEntity.ok().eTag(ETags.of(details.project().version())).body(details);
    }

    @Operation(operationId = "updateProject", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(mediaType = "application/merge-patch+json",
                    schema = @Schema(implementation = ProjectPatchRequest.class))))
    @PatchMapping(path = "/{key}", consumes = {"application/merge-patch+json", "application/json"})
    ResponseEntity<ProjectView> update(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch,
            @RequestBody Map<String, @Nullable Object> body) {
        ProjectView project = projects.update(principal.id(), key, ETags.versionFrom(ifMatch),
                ProjectPatchRequest.parse(body));
        return ResponseEntity.ok().eTag(ETags.of(project.version())).body(project);
    }

    @Operation(operationId = "archiveProject")
    @PostMapping("/{key}/archive")
    ResponseEntity<Void> archive(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        projects.archive(principal.id(), key);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "starProject")
    @PutMapping("/{key}/star")
    ResponseEntity<Void> star(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        projects.star(principal.id(), key);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "unstarProject")
    @DeleteMapping("/{key}/star")
    ResponseEntity<Void> unstar(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        projects.unstar(principal.id(), key);
        return ResponseEntity.noContent().build();
    }
}

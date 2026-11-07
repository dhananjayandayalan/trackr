package dev.trakr.api.sprint.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.shared.web.ETags;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.api.sprint.BacklogFilter;
import dev.trakr.api.sprint.BacklogView;
import dev.trakr.api.sprint.SprintCommands.CompleteSprint;
import dev.trakr.api.sprint.SprintCommands.Field;
import dev.trakr.api.sprint.SprintCommands.SprintPatch;
import dev.trakr.api.sprint.SprintCommands.StartSprint;
import dev.trakr.api.sprint.SprintService;
import dev.trakr.api.sprint.SprintView;
import dev.trakr.contracts.events.SprintReport;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 03-api §3.7: sprints and the backlog. */
@RestController
class SprintController {

    private static final Set<String> TYPES = Set.of("story", "bug", "task");

    record CreateSprintRequest(@Nullable @Size(max = 80) String name) {
    }

    record StartSprintRequest(@NotBlank @Size(max = 80) String name, @Nullable @Size(max = 500) String goal,
            @NotNull LocalDate startsOn, @NotNull LocalDate endsOn) {
    }

    /** {@code moveOpenIssuesTo}: "backlog" or a planned sprint's id. */
    record CompleteSprintRequest(@NotBlank String moveOpenIssuesTo) {
    }

    private final SprintService sprints;

    SprintController(SprintService sprints) {
        this.sprints = sprints;
    }

    @Operation(operationId = "getBacklog")
    @GetMapping("/api/v1/projects/{key}/backlog")
    BacklogView backlog(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestParam(required = false) @Nullable UUID epic,
            @RequestParam(required = false) @Nullable String type,
            @RequestParam(required = false) @Nullable String q) {
        if (type != null && !TYPES.contains(type)) {
            throw invalid(List.of(new FieldMessage("type", "must be story, bug or task")));
        }
        return sprints.backlog(principal.id(), key, new BacklogFilter(epic, type, q));
    }

    @Operation(operationId = "createSprint")
    @PostMapping("/api/v1/projects/{key}/sprints")
    ResponseEntity<SprintView> create(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @Valid @RequestBody CreateSprintRequest body) {
        SprintView sprint = sprints.create(principal.id(), key, body.name());
        return ResponseEntity.created(URI.create("/api/v1/sprints/" + sprint.id())).eTag(ETags.of(sprint.version()))
                .body(sprint);
    }

    @Operation(operationId = "getSprint")
    @GetMapping("/api/v1/sprints/{id}")
    ResponseEntity<SprintView> get(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id) {
        return withETag(sprints.get(principal.id(), id));
    }

    @Operation(operationId = "updateSprint")
    @PatchMapping(path = "/api/v1/sprints/{id}", consumes = {"application/merge-patch+json", "application/json"})
    ResponseEntity<SprintView> update(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch,
            @RequestBody Map<String, @Nullable Object> body) {
        long version = ETags.versionFrom(ifMatch);
        return withETag(sprints.update(principal.id(), id, version, parsePatch(body)));
    }

    @Operation(operationId = "startSprint")
    @PostMapping("/api/v1/sprints/{id}/start")
    ResponseEntity<SprintView> start(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody StartSprintRequest body) {
        return withETag(sprints.start(principal.id(), id,
                new StartSprint(body.name(), body.goal(), body.startsOn(), body.endsOn())));
    }

    @Operation(operationId = "completeSprint")
    @PostMapping("/api/v1/sprints/{id}/complete")
    SprintReport complete(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody CompleteSprintRequest body) {
        UUID target = null;
        if (!"backlog".equals(body.moveOpenIssuesTo())) {
            try {
                target = UUID.fromString(body.moveOpenIssuesTo());
            } catch (IllegalArgumentException e) {
                throw invalid(List.of(new FieldMessage("moveOpenIssuesTo", "must be \"backlog\" or a sprint id")));
            }
        }
        return sprints.complete(principal.id(), id, new CompleteSprint(target));
    }

    @Operation(operationId = "deleteSprint")
    @DeleteMapping("/api/v1/sprints/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id) {
        sprints.delete(principal.id(), id);
        return ResponseEntity.noContent().build();
    }

    // merge patch: name (can't be cleared), goal / startsOn / endsOn (null clears)
    private static SprintPatch parsePatch(Map<String, @Nullable Object> body) {
        List<FieldMessage> errors = new ArrayList<>();
        body.keySet().stream().filter(field -> !Set.of("name", "goal", "startsOn", "endsOn").contains(field))
                .forEach(field -> errors.add(new FieldMessage(field, "is not a field you can change")));
        String name = null;
        if (body.containsKey("name")) {
            if (body.get("name") instanceof String value && !value.isBlank() && value.length() <= 80) {
                name = value;
            } else {
                errors.add(new FieldMessage("name", "must be 1 to 80 characters"));
            }
        }
        Field<String> goal = null;
        if (body.containsKey("goal")) {
            Object value = body.get("goal");
            if (value == null || (value instanceof String text && text.length() <= 500)) {
                goal = new Field<>((String) value);
            } else {
                errors.add(new FieldMessage("goal", "must be at most 500 characters"));
            }
        }
        Field<LocalDate> startsOn = date(body, "startsOn", errors);
        Field<LocalDate> endsOn = date(body, "endsOn", errors);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return new SprintPatch(name, goal, startsOn, endsOn);
    }

    private static @Nullable Field<LocalDate> date(Map<String, @Nullable Object> body, String field,
            List<FieldMessage> errors) {
        if (!body.containsKey(field)) {
            return null;
        }
        Object value = body.get(field);
        if (value == null) {
            return new Field<>(null);
        }
        try {
            return new Field<>(LocalDate.parse(String.valueOf(value)));
        } catch (DateTimeParseException e) {
            errors.add(new FieldMessage(field, "must be a date like 2026-10-22"));
            return null;
        }
    }

    private static ResponseEntity<SprintView> withETag(SprintView sprint) {
        return ResponseEntity.ok().eTag(ETags.of(sprint.version())).body(sprint);
    }

    private static TrakrException invalid(List<FieldMessage> errors) {
        return new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request has invalid fields.", errors);
    }
}

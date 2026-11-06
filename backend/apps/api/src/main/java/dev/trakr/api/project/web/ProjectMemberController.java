package dev.trakr.api.project.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.project.MemberView;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.project.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{key}/members")
class ProjectMemberController {

    record AddMemberRequest(@NotBlank @Email String email, @NotNull ProjectRole role) {
    }

    record ChangeRoleRequest(@NotNull ProjectRole role) {
    }

    private final ProjectService projects;

    ProjectMemberController(ProjectService projects) {
        this.projects = projects;
    }

    @Operation(operationId = "listProjectMembers")
    @GetMapping
    List<MemberView> list(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key) {
        return projects.members(principal.id(), key);
    }

    // 201 with the member, or 202 when the email has no account yet and an invitation goes out instead
    @Operation(operationId = "addProjectMember")
    @PostMapping
    ResponseEntity<MemberView> add(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @Valid @RequestBody AddMemberRequest body) {
        return projects.addMember(principal.id(), key, body.email(), body.role())
                .map(member -> ResponseEntity.status(HttpStatus.CREATED).body(member))
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

    @Operation(operationId = "changeProjectMemberRole")
    @PatchMapping("/{userId}")
    MemberView changeRole(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @PathVariable UUID userId, @Valid @RequestBody ChangeRoleRequest body) {
        return projects.changeRole(principal.id(), key, userId, body.role());
    }

    @Operation(operationId = "removeProjectMember")
    @DeleteMapping("/{userId}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @PathVariable UUID userId) {
        projects.removeMember(principal.id(), key, userId);
        return ResponseEntity.noContent().build();
    }
}

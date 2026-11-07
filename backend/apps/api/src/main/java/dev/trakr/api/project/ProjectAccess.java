package dev.trakr.api.project;

import dev.trakr.api.project.internal.Project;
import dev.trakr.api.project.internal.ProjectMember;
import dev.trakr.api.project.internal.ProjectMemberRepository;
import dev.trakr.api.project.internal.ProjectRepository;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.workspace.WorkspaceService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may do what in a project (09-security §3). Spring Security only knows "signed in or not";
 * this is where project roles are checked, by every module that works inside a project.
 */
@Component("projectAccess")
public class ProjectAccess {

    /** Proof that the check passed: the project it was for and the caller's effective role there. */
    public record Grant(UUID projectId, UUID workspaceId, String key, ProjectRole role) {
    }

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final WorkspaceService workspaces;

    ProjectAccess(ProjectRepository projects, ProjectMemberRepository members, WorkspaceService workspaces) {
        this.projects = projects;
        this.members = members;
        this.workspaces = workspaces;
    }

    /**
     * 404 when the caller can't see the project — the same answer as "no such key", so nobody learns
     * which private projects exist; 403 when they can see it but their role is too weak.
     *
     * <p>URLs carry only the key, and keys are unique per workspace, so the key is looked up in every
     * workspace the caller belongs to; if two of them use the same key, the caller's own workspace wins.
     */
    @Transactional(readOnly = true)
    public Grant require(UUID userId, String key, ProjectRole minimum) {
        Project project = workspaces.workspaceIdsOf(userId).stream()
                .flatMap(workspaceId -> projects.findByWorkspaceIdAndKey(workspaceId, key).stream())
                .findFirst()
                .orElseThrow(() -> notFound(key));
        ProjectRole role = roleOf(userId, project).orElseThrow(() -> notFound(key));
        if (!role.atLeast(minimum)) {
            throw new TrakrException.Forbidden("This needs the " + minimum.value() + " role in " + key + ".");
        }
        return new Grant(project.id(), project.workspaceId(), project.key(), role);
    }

    /**
     * The same check for things addressed by id (a sprint, a link) whose project is known but whose URL
     * has no key. 404 again when the caller can't see the project.
     */
    @Transactional(readOnly = true)
    public Grant require(UUID userId, UUID projectId, ProjectRole minimum) {
        Project project = projects.findById(projectId)
                .filter(candidate -> workspaces.isMember(candidate.workspaceId(), userId))
                .orElseThrow(() -> new TrakrException.NotFound("No such project."));
        ProjectRole role = roleOf(userId, project).orElseThrow(() -> new TrakrException.NotFound("No such project."));
        if (!role.atLeast(minimum)) {
            throw new TrakrException.Forbidden("This needs the " + minimum.value() + " role in " + project.key() + ".");
        }
        return new Grant(project.id(), project.workspaceId(), project.key(), role);
    }

    /**
     * The user's effective role in a project they may not be calling about, e.g. "can this person be
     * assigned issues here?"; empty when they can't see it at all.
     */
    @Transactional(readOnly = true)
    public Optional<ProjectRole> roleIn(UUID userId, UUID projectId) {
        return projects.findById(projectId)
                // the access level only gives roles to members of the project's workspace
                .filter(project -> workspaces.isMember(project.workspaceId(), userId))
                .flatMap(project -> roleOf(userId, project));
    }

    // the stronger of: an explicit membership, and what the access level gives every workspace member
    private Optional<ProjectRole> roleOf(UUID userId, Project project) {
        Optional<ProjectRole> explicit = members.findById(new ProjectMember.Key(project.id(), userId))
                .map(ProjectMember::role);
        Optional<ProjectRole> implied = switch (project.access()) {
            case OPEN -> Optional.of(ProjectRole.MEMBER);
            case LIMITED -> Optional.of(ProjectRole.VIEWER);
            case PRIVATE -> Optional.empty();
        };
        if (explicit.isPresent() && implied.isPresent()) {
            return Optional.of(explicit.get().atLeast(implied.get()) ? explicit.get() : implied.get());
        }
        return explicit.or(() -> implied);
    }

    private static TrakrException.NotFound notFound(String key) {
        return new TrakrException.NotFound("No project " + key + ".");
    }
}

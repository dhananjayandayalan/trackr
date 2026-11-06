package dev.trakr.api.workspace;

import dev.trakr.api.workspace.internal.Workspace;
import dev.trakr.api.workspace.internal.WorkspaceMember;
import dev.trakr.api.workspace.internal.WorkspaceMemberRepository;
import dev.trakr.api.workspace.internal.WorkspaceQueries;
import dev.trakr.api.workspace.internal.WorkspaceRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workspace module's public API. It takes plain values, not identity types: identity calls
 * this module at sign-up, so depending back on identity would make a cycle.
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final WorkspaceQueries queries;
    private final Clock clock;

    WorkspaceService(WorkspaceRepository workspaces, WorkspaceMemberRepository members, WorkspaceQueries queries,
            Clock clock) {
        this.workspaces = workspaces;
        this.members = members;
        this.queries = queries;
        this.clock = clock;
    }

    /** A new workspace owned by the user; joins the caller's transaction (sign-up). */
    @Transactional
    public CurrentWorkspace createPersonalWorkspace(UUID userId, String ownerName) {
        Workspace workspace = workspaces.save(Workspace.personalFor(ownerName, clock));
        WorkspaceMember owner = members.save(WorkspaceMember.owner(workspace.id(), userId, clock));
        return new CurrentWorkspace(workspace.id(), workspace.name(), workspace.slug(), owner.role());
    }

    @Transactional(readOnly = true)
    public Optional<CurrentWorkspace> currentFor(UUID userId) {
        return queries.firstWorkspaceOf(userId);
    }

    /**
     * Every workspace the user belongs to, their own first. Being added to someone else's project
     * also adds them to that workspace, so this can be more than one.
     */
    @Transactional(readOnly = true)
    public List<UUID> workspaceIdsOf(UUID userId) {
        return queries.workspaceIdsOf(userId);
    }

    @Transactional(readOnly = true)
    public boolean isMember(UUID workspaceId, UUID userId) {
        return members.existsById(new WorkspaceMember.Key(workspaceId, userId));
    }

    /** Adds the user as a plain member; does nothing if they already belong (owners stay owners). */
    @Transactional
    public void addMember(UUID workspaceId, UUID userId) {
        if (!isMember(workspaceId, userId)) {
            members.save(WorkspaceMember.member(workspaceId, userId, clock));
        }
    }
}

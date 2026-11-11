package dev.trakr.api.project;

import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.project.ProjectAccess.Grant;
import dev.trakr.api.project.internal.KeySuggester;
import dev.trakr.api.project.internal.Project;
import dev.trakr.api.project.internal.ProjectMember;
import dev.trakr.api.project.internal.ProjectMemberRepository;
import dev.trakr.api.project.internal.ProjectQueries;
import dev.trakr.api.project.internal.ProjectRepository;
import dev.trakr.api.project.internal.ProjectStar;
import dev.trakr.api.project.internal.ProjectStarRepository;
import dev.trakr.api.project.internal.ProjectVisit;
import dev.trakr.api.project.internal.ProjectVisitRepository;
import dev.trakr.api.shared.web.ETags;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.api.workspace.CurrentWorkspace;
import dev.trakr.api.workspace.WorkspaceService;
import dev.trakr.contracts.events.ProjectLifecycle;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The project module's public API. Every method starts with an access check. */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectStarRepository stars;
    private final ProjectVisitRepository visits;
    private final ProjectQueries queries;
    private final ProjectAccess access;
    private final KeySuggester keySuggester;
    private final WorkspaceService workspaces;
    private final IdentityService identities;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ProjectService(ProjectRepository projects, ProjectMemberRepository members, ProjectStarRepository stars,
            ProjectVisitRepository visits, ProjectQueries queries, ProjectAccess access, KeySuggester keySuggester,
            WorkspaceService workspaces, IdentityService identities, ApplicationEventPublisher events, Clock clock) {
        this.projects = projects;
        this.members = members;
        this.stars = stars;
        this.visits = visits;
        this.queries = queries;
        this.access = access;
        this.keySuggester = keySuggester;
        this.workspaces = workspaces;
        this.identities = identities;
        this.events = events;
        this.clock = clock;
    }

    /**
     * One transaction: the project, its admin, the seeded workflow and board (the listeners run
     * inside publishEvent) and the invites. If any of it fails, none of it exists.
     */
    @Transactional
    public ProjectView create(UUID userId, NewProject command) {
        // BLOCK 1: authorize + validate
        CurrentWorkspace workspace = workspaces.currentFor(userId)
                .orElseThrow(() -> new TrakrException.Forbidden("You are not in a workspace."));
        UUID leadId = command.leadId() == null ? userId : command.leadId();
        requireWorkspaceMember(workspace.id(), leadId);

        // BLOCK 2: create aggregate. No "is the key free?" query first: another request could take the key
        // between that check and our INSERT. The unique constraint is the only check that can't race.
        Project project = Project.create(workspace.id(), command.key(), command.name(), command.description(),
                command.template(), leadId, command.access(), command.color(), clock);
        try {
            project = projects.saveAndFlush(project);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_project_workspace_key")) {
                throw new TrakrException.Conflict("PROJECT_KEY_TAKEN", "Key " + command.key() + " is already used.");
            }
            throw e;
        }
        members.save(ProjectMember.add(project.id(), userId, ProjectRole.ADMIN, clock));
        if (!leadId.equals(userId)) {
            members.save(ProjectMember.add(project.id(), leadId, ProjectRole.ADMIN, clock));
        }

        // BLOCK 3: publish — workflow and board seed the project synchronously, in this transaction;
        // the contracts event goes to the outbox and on to Kafka after commit
        events.publishEvent(new ProjectCreated(project.id(), workspace.id(), project.key(), project.template(), userId));
        events.publishEvent(ProjectLifecycle.Created.of(clock.instant(), userId, project.snapshot()));

        // BLOCK 4: invites — existing users become members now; unknown emails get an invitation later
        for (String email : command.invite()) {
            invite(project, email, ProjectRole.MEMBER, userId);
        }
        return project.toView();
    }

    /** Visible projects across every workspace the user belongs to (see ProjectAccess.require). */
    @Transactional(readOnly = true)
    public List<ProjectSummary> list(UUID userId, ProjectFilter filter) {
        return queries.listVisible(userId, workspaces.workspaceIdsOf(userId), filter);
    }

    /** The project screen's data; also records the visit for "Recent projects". */
    @Transactional
    public ProjectDetails open(UUID userId, String key) {
        Grant grant = access.require(userId, key, ProjectRole.VIEWER);
        visits.save(ProjectVisit.now(userId, grant.projectId(), clock));
        Project project = projects.findById(grant.projectId()).orElseThrow();
        return new ProjectDetails(project.toView(), queries.statusesOf(project.id()),
                queries.transitionsOf(project.id()), queries.boardOf(project.id()).orElse(null),
                queries.membersOf(project.id()), grant.role());
    }

    /** {@code expectedVersion} comes from If-Match; a different current version is a 412 with the current project. */
    @Transactional
    public ProjectView update(UUID userId, String key, long expectedVersion, ProjectPatch patch) {
        Grant grant = access.require(userId, key, ProjectRole.ADMIN);
        Project project = projects.findById(grant.projectId()).orElseThrow();
        ETags.requireMatch(expectedVersion, project.version(), project.toView());

        if (patch.name() != null) {
            project.rename(patch.name(), clock);
        }
        if (patch.descriptionPresent()) {
            project.describe(patch.description(), clock);
        }
        if (patch.leadId() != null) {
            requireWorkspaceMember(grant.workspaceId(), patch.leadId());
            project.changeLead(patch.leadId(), clock);
        }
        if (patch.access() != null) {
            project.changeAccess(patch.access(), clock);
        }
        if (patch.color() != null) {
            project.recolor(patch.color(), clock);
        }
        // dirty checking writes the UPDATE; flush now so the new version is in the response and the event
        projects.flush();
        events.publishEvent(new ProjectUpdated(project.id(), project.workspaceId(), project.key(), project.version(), userId));
        events.publishEvent(ProjectLifecycle.Updated.of(clock.instant(), userId, project.snapshot()));
        return project.toView();
    }

    @Transactional
    public void archive(UUID userId, String key) {
        Grant grant = access.require(userId, key, ProjectRole.ADMIN);
        Project project = projects.findById(grant.projectId()).orElseThrow();
        project.archive(clock);
        projects.flush();
        events.publishEvent(new ProjectArchived(grant.projectId(), grant.workspaceId(), grant.key(), userId));
        events.publishEvent(ProjectLifecycle.Archived.of(clock.instant(), userId, project.snapshot()));
    }

    @Transactional
    public void star(UUID userId, String key) {
        Grant grant = access.require(userId, key, ProjectRole.VIEWER);
        stars.save(ProjectStar.of(userId, grant.projectId(), clock));
    }

    @Transactional
    public void unstar(UUID userId, String key) {
        Grant grant = access.require(userId, key, ProjectRole.VIEWER);
        stars.deleteById(new ProjectStar.Key(userId, grant.projectId()));
    }

    /** The timeline screen (phase 17): epics and their children; {@code from}/{@code to} narrow it to a window. */
    @Transactional(readOnly = true)
    public TimelineView timeline(UUID userId, String key, java.time.@Nullable LocalDate from,
            java.time.@Nullable LocalDate to) {
        return new TimelineView(queries.timeline(access.require(userId, key, ProjectRole.VIEWER).projectId(), from, to));
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(UUID userId, String key) {
        return queries.membersOf(access.require(userId, key, ProjectRole.VIEWER).projectId());
    }

    /** The member, or empty when the email has no account yet and an invitation was requested instead. */
    @Transactional
    public Optional<MemberView> addMember(UUID userId, String key, String email, ProjectRole role) {
        Grant grant = access.require(userId, key, ProjectRole.ADMIN);
        return invite(projects.findById(grant.projectId()).orElseThrow(), email, role, userId);
    }

    @Transactional
    public MemberView changeRole(UUID userId, String key, UUID memberId, ProjectRole role) {
        Grant grant = access.require(userId, key, ProjectRole.ADMIN);
        ProjectMember member = memberOf(grant, memberId);
        if (member.role() == ProjectRole.ADMIN && role != ProjectRole.ADMIN) {
            requireAnotherAdmin(grant);
        }
        ProjectRole previous = member.role();
        member.changeRole(role);
        events.publishEvent(new ProjectMemberChanged(grant.projectId(), grant.workspaceId(), memberId, role, previous,
                userId));
        publishMemberChanged(projects.findById(grant.projectId()).orElseThrow(), memberId, role, userId);
        return toView(memberId, role);
    }

    @Transactional
    public void removeMember(UUID userId, String key, UUID memberId) {
        Grant grant = access.require(userId, key, ProjectRole.ADMIN);
        ProjectMember member = memberOf(grant, memberId);
        if (member.role() == ProjectRole.ADMIN) {
            requireAnotherAdmin(grant);
        }
        members.delete(member);
        events.publishEvent(new ProjectMemberChanged(grant.projectId(), grant.workspaceId(), memberId, null,
                member.role(), userId));
        publishMemberChanged(projects.findById(grant.projectId()).orElseThrow(), memberId, null, userId);
    }

    /** The first suggested key that is free in the caller's workspace. */
    @Transactional(readOnly = true)
    public KeySuggestion suggestKey(UUID userId, String name) {
        List<String> candidates = keySuggester.candidates(name);
        Optional<UUID> workspaceId = workspaces.currentFor(userId).map(CurrentWorkspace::id);
        return candidates.stream()
                .filter(key -> workspaceId.map(id -> !projects.existsByWorkspaceIdAndKey(id, key)).orElse(true))
                .findFirst()
                .map(key -> new KeySuggestion(key, true))
                .orElseGet(() -> new KeySuggestion(candidates.getFirst(), false));
    }

    private Optional<MemberView> invite(Project project, String email, ProjectRole role, UUID invitedBy) {
        Optional<UserView> user = identities.findByEmail(email);
        if (user.isEmpty()) {
            events.publishEvent(new ProjectInviteRequested(project.id(), email, role, invitedBy));
            return Optional.empty();
        }
        UUID memberId = user.get().id();
        // a project member must belong to the project's workspace, or they could never open it
        workspaces.addMember(project.workspaceId(), memberId);
        ProjectMember.Key memberKey = new ProjectMember.Key(project.id(), memberId);
        ProjectMember member = members.findById(memberKey).orElse(null);
        ProjectRole previous = member == null ? null : member.role();
        if (member == null) {
            members.save(ProjectMember.add(project.id(), memberId, role, clock));
        } else {
            member.changeRole(role);
        }
        events.publishEvent(new ProjectMemberChanged(project.id(), project.workspaceId(), memberId, role, previous,
                invitedBy));
        publishMemberChanged(project, memberId, role, invitedBy);
        return Optional.of(toView(user.get(), role));
    }

    // bump the project's version first: the event's sequence must be newer than the last one about this project
    private void publishMemberChanged(Project project, UUID memberId, @Nullable ProjectRole role, UUID actorId) {
        project.membersChanged(clock);
        projects.flush();
        events.publishEvent(ProjectLifecycle.MemberChanged.of(clock.instant(), actorId, project.snapshot(), memberId,
                role == null ? null : role.value()));
    }

    private ProjectMember memberOf(Grant grant, UUID memberId) {
        return members.findById(new ProjectMember.Key(grant.projectId(), memberId))
                .orElseThrow(() -> new TrakrException.NotFound("No such member in " + grant.key() + "."));
    }

    private void requireAnotherAdmin(Grant grant) {
        if (members.countByProjectIdAndRole(grant.projectId(), ProjectRole.ADMIN.value()) <= 1) {
            throw new TrakrException.Conflict("LAST_ADMIN", "A project needs at least one admin.");
        }
    }

    private void requireWorkspaceMember(UUID workspaceId, UUID leadId) {
        if (!workspaces.isMember(workspaceId, leadId)) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The lead must be in this workspace.",
                    List.of(new FieldMessage("leadId", "is not a member of this workspace")));
        }
    }

    private MemberView toView(UUID memberId, ProjectRole role) {
        return toView(identities.findById(memberId).orElseThrow(), role);
    }

    private static MemberView toView(UserView user, ProjectRole role) {
        return new MemberView(user.id(), user.displayName(), user.initials(), user.avatarColor(), role);
    }
}

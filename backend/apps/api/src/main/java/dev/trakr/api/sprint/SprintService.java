package dev.trakr.api.sprint;

import dev.trakr.api.issue.IssueService;
import dev.trakr.api.project.ProjectAccess;
import dev.trakr.api.project.ProjectAccess.Grant;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.api.sprint.SprintCommands.CompleteSprint;
import dev.trakr.api.sprint.SprintCommands.SprintPatch;
import dev.trakr.api.sprint.SprintCommands.StartSprint;
import dev.trakr.api.sprint.internal.Sprint;
import dev.trakr.api.sprint.internal.SprintQueries;
import dev.trakr.api.sprint.internal.SprintQueries.SprintIssue;
import dev.trakr.api.sprint.internal.SprintRepository;
import dev.trakr.contracts.events.SprintEvent.SprintCompleted;
import dev.trakr.contracts.events.SprintEvent.SprintCreated;
import dev.trakr.contracts.events.SprintEvent.SprintDeleted;
import dev.trakr.contracts.events.SprintEvent.SprintStarted;
import dev.trakr.contracts.events.SprintEvent.SprintUpdated;
import dev.trakr.contracts.events.SprintReport;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sprint module's public API (03-api §3.7). Sprints are addressed by id; the access check goes
 * through the sprint's project. Only scrum projects have sprints (00-product §5).
 */
@Service
public class SprintService {

    private final SprintRepository sprints;
    private final SprintQueries queries;
    private final IssueService issues;
    private final ProjectAccess access;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SprintService(SprintRepository sprints, SprintQueries queries, IssueService issues, ProjectAccess access,
            ApplicationEventPublisher events, Clock clock) {
        this.sprints = sprints;
        this.queries = queries;
        this.issues = issues;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    /** A planned sprint at the end of the list; default name "KEY Sprint N". */
    @Transactional
    public SprintView create(UUID userId, String projectKey, String name) {
        Grant grant = access.require(userId, projectKey, ProjectRole.MEMBER);
        requireScrum(grant);
        String sprintName = name == null || name.isBlank()
                ? grant.key() + " Sprint " + (sprints.countByProjectId(grant.projectId()) + 1)
                : name.strip();
        Sprint sprint = sprints.saveAndFlush(Sprint.plan(grant.projectId(), sprintName,
                sprints.maxPosition(grant.projectId()) + 1, clock));
        events.publishEvent(SprintCreated.of(clock.instant(), userId, sprint.snapshot(grant.workspaceId())));
        return sprint.toView();
    }

    @Transactional
    public SprintView update(UUID userId, UUID sprintId, long expectedVersion, SprintPatch patch) {
        Sprint sprint = load(sprintId);
        Grant grant = grant(userId, sprint, ProjectRole.MEMBER);
        if (expectedVersion != sprint.version()) {
            throw new TrakrException.VersionMismatch(sprint.toView());
        }
        boolean changed = false;
        if (patch.name() != null) {
            changed |= sprint.rename(patch.name().strip());
        }
        if (patch.goal() != null) {
            changed |= sprint.updateGoal(patch.goal().value());
        }
        if (patch.startsOn() != null || patch.endsOn() != null) {
            SprintView current = sprint.toView();
            changed |= sprint.updateDates(patch.startsOn() == null ? current.startsOn() : patch.startsOn().value(),
                    patch.endsOn() == null ? current.endsOn() : patch.endsOn().value());
        }
        if (changed) {
            sprints.flush();
            events.publishEvent(SprintUpdated.of(clock.instant(), userId, sprint.snapshot(grant.workspaceId())));
        }
        return sprint.toView();
    }

    /**
     * Starts a planned sprint and records what it commits to. The "is another one active?" check gives a
     * clear 409 in the normal case; the partial unique index catches the race where two people start
     * sprints at the same moment — both pass the check, only one INSERT… UPDATE survives the index.
     */
    @Transactional
    public SprintView start(UUID userId, UUID sprintId, StartSprint cmd) {
        Sprint sprint = load(sprintId);
        Grant grant = grant(userId, sprint, ProjectRole.MEMBER);
        if (sprints.existsByProjectIdAndState(sprint.projectId(), SprintState.ACTIVE.value())) {
            throw alreadyActive(grant);
        }
        List<SprintIssue> committed = queries.issuesOf(sprintId);
        BigDecimal points = committed.stream().map(SprintIssue::points).reduce(BigDecimal.ZERO, BigDecimal::add);
        sprint.start(cmd.name().strip(), cmd.goal(), cmd.startsOn(), cmd.endsOn(), points,
                committed.stream().map(SprintIssue::id).toList(), clock);
        try {
            sprints.saveAndFlush(sprint);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_sprint_one_active")) {
                throw alreadyActive(grant);
            }
            throw e;
        }
        events.publishEvent(SprintStarted.of(clock.instant(), userId, sprint.snapshot(grant.workspaceId())));
        return sprint.toView();
    }

    /**
     * Closes the active sprint (00-product §4.5): the report is computed first, from the sprint as it
     * ends; then the unfinished issues go to the top of the backlog (in their order) or to the bottom of
     * a planned sprint.
     */
    @Transactional
    public SprintReport complete(UUID userId, UUID sprintId, CompleteSprint cmd) {
        // 1. lock: a second "complete" waits here, then finds the sprint closed
        Sprint sprint = sprints.findLockedById(sprintId).orElseThrow(SprintService::notFound);
        Grant grant = grant(userId, sprint, ProjectRole.MEMBER);
        UUID target = cmd.moveOpenIssuesToSprintId();
        if (target != null && (target.equals(sprintId) || !queries.isPlannedSprintOf(grant.projectId(), target))) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Open issues can only move to the "
                    + "backlog or a planned sprint of " + grant.key() + ".",
                    List.of(new FieldMessage("moveOpenIssuesTo", "is not a planned sprint of " + grant.key())));
        }
        sprint.complete(clock);

        // 2–4. report, from the sprint's issues at the end compared with its commitment at the start
        List<SprintIssue> atEnd = queries.issuesOf(sprintId);
        Set<UUID> committedIds = new HashSet<>(sprint.committedIssueIds());
        List<UUID> completed = atEnd.stream().filter(i -> "done".equals(i.statusCategory())).map(SprintIssue::id).toList();
        List<UUID> incomplete = atEnd.stream().filter(i -> !"done".equals(i.statusCategory())).map(SprintIssue::id).toList();
        SprintReport report = new SprintReport(sprintId, sprint.committedPoints(),
                sum(atEnd.stream().filter(i -> "done".equals(i.statusCategory())).toList()),
                sum(atEnd.stream().filter(i -> !committedIds.contains(i.id())).toList()),
                queries.pointsLeft(sprintId, committedIds), completed, incomplete, clock.instant());
        issues.moveSprintIssues(userId, grant.projectId(), sprintId, target, true);

        // 5–6. close and tell everyone; IssueMoved per moved issue was published by the issue module
        sprints.flush();
        events.publishEvent(SprintCompleted.of(clock.instant(), userId, sprint.snapshot(grant.workspaceId()), report));
        return report;
    }

    /** Planned sprints only; their issues return to the top of the backlog. */
    @Transactional
    public void delete(UUID userId, UUID sprintId) {
        Sprint sprint = load(sprintId);
        Grant grant = grant(userId, sprint, ProjectRole.MEMBER);
        sprint.requireDeletable();
        issues.moveSprintIssues(userId, grant.projectId(), sprintId, null, false);
        sprints.delete(sprint);
        sprints.flush();
        events.publishEvent(SprintDeleted.of(clock.instant(), userId, sprint.snapshot(grant.workspaceId())));
    }

    @Transactional(readOnly = true)
    public SprintView get(UUID userId, UUID sprintId) {
        Sprint sprint = load(sprintId);
        grant(userId, sprint, ProjectRole.VIEWER);
        return sprint.toView();
    }

    @Transactional(readOnly = true)
    public BacklogView backlog(UUID userId, String projectKey, BacklogFilter filter) {
        Grant grant = access.require(userId, projectKey, ProjectRole.VIEWER);
        return queries.backlog(grant.projectId(), filter);
    }

    private Sprint load(UUID sprintId) {
        return sprints.findById(sprintId).orElseThrow(SprintService::notFound);
    }

    // an invisible project's sprint is "no such sprint", the same as one that doesn't exist
    private Grant grant(UUID userId, Sprint sprint, ProjectRole minimum) {
        try {
            return access.require(userId, sprint.projectId(), minimum);
        } catch (TrakrException.NotFound e) {
            throw notFound();
        }
    }

    private void requireScrum(Grant grant) {
        if (!"scrum".equals(queries.templateOf(grant.projectId()))) {
            throw new TrakrException.Conflict("SPRINTS_NOT_ENABLED", grant.key() + " is not a scrum project.");
        }
    }

    private static BigDecimal sum(List<SprintIssue> issues) {
        return issues.stream().map(SprintIssue::points).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static TrakrException alreadyActive(Grant grant) {
        return new TrakrException.Conflict("SPRINT_ALREADY_ACTIVE", grant.key() + " already has an active sprint.");
    }

    private static TrakrException.NotFound notFound() {
        return new TrakrException.NotFound("No such sprint.");
    }
}

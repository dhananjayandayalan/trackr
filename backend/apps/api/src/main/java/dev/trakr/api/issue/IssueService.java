package dev.trakr.api.issue;

import dev.trakr.api.issue.IssueDetails.IssueRef;
import dev.trakr.api.issue.IssueDetails.LinkView;
import dev.trakr.api.issue.internal.FieldChange;
import dev.trakr.api.issue.internal.Issue;
import dev.trakr.api.issue.internal.IssueHistory;
import dev.trakr.api.issue.internal.IssueLink;
import dev.trakr.api.issue.internal.IssueLinkRepository;
import dev.trakr.api.issue.internal.IssueMarks;
import dev.trakr.api.issue.internal.IssueNumberAllocator;
import dev.trakr.api.issue.internal.IssueQueries;
import dev.trakr.api.issue.internal.IssueRepository;
import dev.trakr.api.issue.internal.Parent;
import dev.trakr.api.issue.internal.Worklog;
import dev.trakr.api.issue.internal.WorklogRepository;
import dev.trakr.api.project.ProjectAccess;
import dev.trakr.api.project.ProjectAccess.Grant;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.api.workflow.WorkflowService;
import dev.trakr.api.workflow.WorkflowStatusView;
import dev.trakr.contracts.events.IssueCreated;
import dev.trakr.contracts.events.IssueDeleted;
import dev.trakr.contracts.events.IssueLinked;
import dev.trakr.contracts.events.IssueTransitioned;
import dev.trakr.contracts.events.IssueUpdated;
import dev.trakr.contracts.events.WorkLogged;
import dev.trakr.kernel.Rank;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The issue module's public API. Every method starts with an access check on the issue's project,
 * found from the key's prefix (MAR-131 → MAR).
 *
 * <p>Events carry {@code sequence = version after the change}. Hibernate bumps the version when it
 * flushes the UPDATE, so each write path flushes before it builds the snapshot; without the flush
 * the snapshot would still show the old version. Creation is sequence 0.
 */
@Service
public class IssueService {

    private static final Pattern ISSUE_KEY = Pattern.compile("^([A-Z]{2,6})-([1-9][0-9]{0,17})$");
    private static final int HISTORY_PAGE = 50;

    private final IssueRepository issues;
    private final IssueLinkRepository links;
    private final WorklogRepository worklogs;
    private final IssueQueries queries;
    private final IssueHistory history;
    private final IssueMarks marks;
    private final IssueNumberAllocator numbers;
    private final ProjectAccess access;
    private final WorkflowService workflow;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    IssueService(IssueRepository issues, IssueLinkRepository links, WorklogRepository worklogs, IssueQueries queries,
            IssueHistory history, IssueMarks marks, IssueNumberAllocator numbers, ProjectAccess access,
            WorkflowService workflow, ApplicationEventPublisher events, Clock clock) {
        this.issues = issues;
        this.links = links;
        this.worklogs = worklogs;
        this.queries = queries;
        this.history = history;
        this.marks = marks;
        this.numbers = numbers;
        this.access = access;
        this.workflow = workflow;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public IssueDetails create(UUID userId, CreateIssue cmd) {
        // BLOCK 1: access check and the checks that need the database — all before the number is taken
        Grant grant = access.require(userId, cmd.projectKey(), ProjectRole.MEMBER);
        if (cmd.assigneeId() != null) {
            requireAssignable(cmd.assigneeId(), grant);
        }
        Parent parent = cmd.parentKey() == null ? null : parentIn(grant, cmd.parentKey());
        if (cmd.sprintId() != null) {
            requireOpenSprint(grant, cmd.sprintId());
        }
        WorkflowStatusView initialStatus = workflow.initialStatus(grant.projectId());

        // BLOCK 2: number, then rank. Taking the number locks the project row until commit, so concurrent
        // creates in this project queue up here — and only then read the last rank, each seeing the one
        // committed before it. Read before the lock, two creates could both pick the same "next" rank.
        long number = numbers.next(grant.projectId());
        String rank = Rank.between(queries.lastRank(grant.projectId(), cmd.sprintId()).orElse(null), null);

        // BLOCK 3: save — flushed now: history, watchers and the detail query all need the row in the database
        Issue issue = issues.saveAndFlush(Issue.create(cmd, grant.workspaceId(), grant.projectId(), number,
                grant.key() + "-" + number, initialStatus, rank, userId, parent, clock));
        history.created(issue, userId);
        marks.watch(issue.id(), userId);
        if (cmd.assigneeId() != null) {
            marks.watch(issue.id(), cmd.assigneeId());
        }

        // BLOCK 4: publish — listeners in this transaction see it now; Kafka gets it from the outbox in phase 09
        events.publishEvent(IssueCreated.of(clock.instant(), userId, issue.snapshot()));
        return detailOf(grant, issue.key(), userId);
    }

    /**
     * Also records the view for "Recently viewed". Synchronously, in this transaction: one upsert, so
     * GET is a write transaction. An async listener (after commit) would keep GET read-only and a bit
     * faster, but needs the event infrastructure of phase 09 — and losing a view on a crash is harmless
     * either way.
     */
    @Transactional
    public IssueDetails get(UUID userId, String key) {
        Grant grant = grant(userId, key, ProjectRole.VIEWER);
        IssueDetails details = detailOf(grant, key, userId);
        marks.recordView(details.id(), userId, clock.instant());
        return details;
    }

    /** {@code expectedVersion} comes from If-Match. Fields set to their current value are not changes. */
    @Transactional
    public IssueDetails patch(UUID userId, String key, long expectedVersion, PatchIssue patch) {
        Grant grant = grant(userId, key, ProjectRole.MEMBER);
        Issue issue = load(grant, key);
        requireVersion(expectedVersion, issue, grant, userId);

        if (patch.assigneeId() != null && patch.assigneeId().value() != null) {
            requireAssignable(patch.assigneeId().value(), grant);
        }
        // the parent the issue will have: needed to check the rules whenever the type or the parent changes
        Parent parentAfter = null;
        if (patch.parentKey() != null) {
            parentAfter = patch.parentKey().value() == null ? null : parentIn(grant, patch.parentKey().value());
        } else if (patch.type() != null && issue.parentId() != null) {
            parentAfter = currentParent(issue);
        }
        Set<IssueType> childTypes = patch.type() == null ? Set.of()
                : issues.childTypes(issue.id()).stream().map(IssueType::fromValue).collect(Collectors.toSet());

        List<FieldChange> changes = issue.apply(patch, parentAfter, childTypes, clock);
        if (changes.isEmpty()) {
            // nothing changed: no UPDATE, same version, no history, no event
            return detailOf(grant, key, userId);
        }
        issues.flush();
        history.changed(issue, userId, changes);
        Map<String, IssueUpdated.Change> byField = new LinkedHashMap<>();
        changes.forEach(change -> byField.put(change.field(),
                new IssueUpdated.Change(change.oldValue(), change.newValue())));
        events.publishEvent(IssueUpdated.of(clock.instant(), userId, byField, issue.snapshot()));
        return detailOf(grant, key, userId);
    }

    @Transactional
    public IssueDetails transition(UUID userId, String key, TransitionIssue cmd) {
        Grant grant = grant(userId, key, ProjectRole.MEMBER);
        Issue issue = load(grant, key);
        requireVersion(cmd.expectedVersion(), issue, grant, userId);
        if (cmd.toStatusId().equals(issue.statusId())) {
            // already there: nothing to do, and no event for a change that didn't happen
            return detailOf(grant, key, userId);
        }

        WorkflowStatusView to = workflow.requireTransition(grant.projectId(), issue.statusId(), cmd.toStatusId());
        WorkflowStatusView from = workflow.status(grant.projectId(), issue.statusId());
        issue.transitionTo(to, clock);
        issues.flush();
        history.transitioned(issue, userId, from, to);
        events.publishEvent(IssueTransitioned.of(clock.instant(), userId, from.id(), issue.snapshot()));
        return detailOf(grant, key, userId);
    }

    /** Soft delete; the subtasks go with it (00-product §4.8). An epic's stories stay, without a parent. */
    @Transactional
    public void delete(UUID userId, String key, long expectedVersion) {
        Grant grant = grant(userId, key, ProjectRole.MEMBER);
        Issue issue = load(grant, key);
        requireVersion(expectedVersion, issue, grant, userId);

        List<Issue> deleted = new ArrayList<>();
        deleted.add(issue);
        issues.findByParentIdAndDeletedAtIsNull(issue.id()).stream()
                .filter(child -> child.type() == IssueType.SUBTASK)
                .forEach(deleted::add);
        deleted.forEach(each -> each.softDelete(clock));
        issues.flush();
        for (Issue each : deleted) {
            history.deleted(each, userId);
            events.publishEvent(IssueDeleted.of(clock.instant(), userId, each.snapshot()));
        }
    }

    /** Newest first, {@value #HISTORY_PAGE} per page. */
    @Transactional(readOnly = true)
    public CursorPage<HistoryItem> history(UUID userId, String key, @Nullable String cursor) {
        Issue issue = load(grant(userId, key, ProjectRole.VIEWER), key);
        Long beforeId = cursor == null ? null : parseCursor(cursor);
        // one extra row tells whether there is a next page, without a COUNT
        List<HistoryItem> rows = queries.history(issue.id(), beforeId, HISTORY_PAGE + 1);
        if (rows.size() <= HISTORY_PAGE) {
            return new CursorPage<>(rows, null);
        }
        List<HistoryItem> page = rows.subList(0, HISTORY_PAGE);
        return new CursorPage<>(page, CursorPage.encode(Long.toString(page.getLast().id())));
    }

    /** The statuses the issue may move to from where it is now. */
    @Transactional(readOnly = true)
    public List<WorkflowStatusView> transitions(UUID userId, String key) {
        Grant grant = grant(userId, key, ProjectRole.VIEWER);
        return workflow.allowedTargets(grant.projectId(), load(grant, key).statusId());
    }

    /** "{@code key} {@code type} {@code targetKey}". The target may be in another project the caller can see. */
    @Transactional
    public LinkView link(UUID userId, String key, String targetKey, LinkType type) {
        Grant grant = grant(userId, key, ProjectRole.MEMBER);
        Issue source = load(grant, key);
        Issue target = load(grant(userId, targetKey, ProjectRole.VIEWER), targetKey);
        if (source.id().equals(target.id())) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "An issue cannot link to itself.",
                    List.of(new FieldMessage("targetKey", "is the issue itself")));
        }

        // no "does it exist?" query first: the unique constraint is the check that can't race
        IssueLink link;
        try {
            link = links.saveAndFlush(IssueLink.create(source.id(), target.id(), type, userId, clock));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_issue_link_source_target_type")) {
                throw new TrakrException.Conflict("LINK_EXISTS", key + " already " + type.label(true) + " " + targetKey + ".");
            }
            throw e;
        }
        source.touch(clock);
        issues.flush();
        history.linked(source, userId, type.label(true), null, Map.of("linkId", link.id().toString(), "key", targetKey));
        events.publishEvent(IssueLinked.of(clock.instant(), userId, source.snapshot(), link.id(), target.id(),
                type.value()));
        IssueRef targetRef = queries.ref(target.id()).orElseThrow();
        return new LinkView(link.id(), type, type.label(true), targetRef);
    }

    /**
     * Removes a link; allowed to members of the source issue's project. The catalogue (04-events §4)
     * has no "unlinked" type, so this is an IssueUpdated with a {@code links} change.
     */
    @Transactional
    public void unlink(UUID userId, UUID linkId) {
        IssueLink link = links.findById(linkId).orElseThrow(() -> new TrakrException.NotFound("No such link."));
        Issue source = issues.findById(link.sourceIssueId()).filter(issue -> !issue.isDeleted())
                .orElseThrow(() -> new TrakrException.NotFound("No such link."));
        grant(userId, source.key(), ProjectRole.MEMBER);
        String targetKey = queries.ref(link.targetIssueId()).map(IssueRef::key).orElse(link.targetIssueId().toString());

        links.delete(link);
        source.touch(clock);
        issues.flush();
        String label = link.type().label(true);
        history.linked(source, userId, label, Map.of("linkId", linkId.toString(), "key", targetKey), null);
        events.publishEvent(IssueUpdated.of(clock.instant(), userId,
                Map.of("links", new IssueUpdated.Change(label + " " + targetKey, null)), source.snapshot()));
    }

    // watching and starring are personal: viewers may do them, and they don't change the issue's version
    @Transactional
    public void watch(UUID userId, String key) {
        marks.watch(load(grant(userId, key, ProjectRole.VIEWER), key).id(), userId);
    }

    @Transactional
    public void unwatch(UUID userId, String key) {
        marks.unwatch(load(grant(userId, key, ProjectRole.VIEWER), key).id(), userId);
    }

    @Transactional
    public void star(UUID userId, String key) {
        marks.star(load(grant(userId, key, ProjectRole.VIEWER), key).id(), userId, clock.instant());
    }

    @Transactional
    public void unstar(UUID userId, String key) {
        marks.unstar(load(grant(userId, key, ProjectRole.VIEWER), key).id(), userId);
    }

    @Transactional
    public WorklogView logWork(UUID userId, String key, NewWorklog cmd) {
        Grant grant = grant(userId, key, ProjectRole.MEMBER);
        Issue issue = load(grant, key);
        Worklog worklog = worklogs.save(Worklog.log(issue.id(), userId, cmd.startedAt(), cmd.minutes(), cmd.note()));
        issue.logWork(worklog.minutes(), clock);
        issues.flush();
        history.loggedWork(issue, userId, worklog.id(), worklog.minutes());
        events.publishEvent(WorkLogged.of(clock.instant(), userId, issue.snapshot(), worklog.minutes()));
        return queries.worklog(worklog.id());
    }

    @Transactional(readOnly = true)
    public List<WorklogView> worklogs(UUID userId, String key) {
        return queries.worklogs(load(grant(userId, key, ProjectRole.VIEWER), key).id());
    }

    // the project from the key's prefix; 404 "No issue …" also when the project is invisible, like a missing key
    private Grant grant(UUID userId, String issueKey, ProjectRole minimum) {
        var matcher = ISSUE_KEY.matcher(issueKey);
        if (!matcher.matches()) {
            throw notFound(issueKey);
        }
        try {
            return access.require(userId, matcher.group(1), minimum);
        } catch (TrakrException.NotFound e) {
            throw notFound(issueKey);
        }
    }

    private Issue load(Grant grant, String key) {
        return issues.findByWorkspaceIdAndKeyAndDeletedAtIsNull(grant.workspaceId(), key)
                .orElseThrow(() -> notFound(key));
    }

    // 412 with the current issue in the body, so the client can merge; the detail query runs only on a mismatch
    private void requireVersion(long expectedVersion, Issue issue, Grant grant, UUID viewerId) {
        if (expectedVersion != issue.version()) {
            throw new TrakrException.VersionMismatch(detailOf(grant, issue.key(), viewerId));
        }
    }

    private IssueDetails detailOf(Grant grant, String key, UUID viewerId) {
        return queries.detail(grant.workspaceId(), key, viewerId).orElseThrow(() -> notFound(key));
    }

    // the parent must be a live issue of the same project; anything else is INVALID_PARENT, not 404
    private Parent parentIn(Grant grant, String parentKey) {
        Issue parent = issues.findByWorkspaceIdAndKeyAndDeletedAtIsNull(grant.workspaceId(), parentKey)
                .filter(candidate -> candidate.projectId().equals(grant.projectId()))
                .orElseThrow(() -> new InvalidParent("No issue " + parentKey + " in " + grant.key() + " to be the parent."));
        return new Parent(parent.id(), parent.type(), parent.projectId());
    }

    private @Nullable Parent currentParent(Issue issue) {
        return issue.parentId() == null ? null : issues.findById(issue.parentId())
                .map(parent -> new Parent(parent.id(), parent.type(), parent.projectId()))
                .orElse(null);
    }

    // viewers can't edit issues, so they can't be given one either
    private void requireAssignable(UUID assigneeId, Grant grant) {
        boolean assignable = access.roleIn(assigneeId, grant.projectId())
                .map(role -> role.atLeast(ProjectRole.MEMBER))
                .orElse(false);
        if (!assignable) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "This person can't be assigned issues in " + grant.key() + ".",
                    List.of(new FieldMessage("assigneeId", "is not a member of " + grant.key())));
        }
    }

    private void requireOpenSprint(Grant grant, UUID sprintId) {
        String state = queries.sprintState(grant.projectId(), sprintId).orElse(null);
        if (state == null || "closed".equals(state)) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "No open sprint with this id in " + grant.key() + ".",
                    List.of(new FieldMessage("sprintId", "is not an open sprint of " + grant.key())));
        }
    }

    private static long parseCursor(String cursor) {
        try {
            return Long.parseLong(CursorPage.decode(cursor));
        } catch (NumberFormatException e) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The cursor is not valid.");
        }
    }

    private static TrakrException.NotFound notFound(String key) {
        return new TrakrException.NotFound("No issue " + key + ".");
    }
}

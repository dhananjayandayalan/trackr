package dev.trakr.api.issue;

import dev.trakr.api.issue.IssueDetails.IssueRef;
import dev.trakr.api.issue.IssueDetails.LinkView;
import dev.trakr.api.issue.internal.FieldChange;
import dev.trakr.api.issue.internal.Issue;
import dev.trakr.api.issue.internal.IssueHistory;
import dev.trakr.api.issue.internal.IssueLink;
import dev.trakr.api.issue.internal.IssueLinkRepository;
import dev.trakr.api.issue.internal.IssueLocks;
import dev.trakr.api.issue.internal.IssueMarks;
import dev.trakr.api.issue.internal.IssueNumberAllocator;
import dev.trakr.api.issue.internal.IssueQueries;
import dev.trakr.api.issue.internal.IssueRepository;
import dev.trakr.api.issue.internal.Parent;
import dev.trakr.api.issue.internal.Worklog;
import dev.trakr.api.issue.internal.WorklogRepository;
import dev.trakr.api.board.BoardColumnView;
import dev.trakr.api.board.BoardService;
import dev.trakr.api.project.ProjectAccess;
import dev.trakr.api.project.ProjectAccess.Grant;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.HtmlSanitizer;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.api.workflow.WorkflowService;
import dev.trakr.api.workflow.WorkflowStatusView;
import dev.trakr.contracts.events.IssueCreated;
import dev.trakr.contracts.events.IssueDeleted;
import dev.trakr.contracts.events.IssueDescriptionChanged;
import dev.trakr.contracts.events.IssueLinked;
import dev.trakr.contracts.events.IssueMoved;
import dev.trakr.contracts.events.IssueSnapshot;
import dev.trakr.contracts.events.IssueTransitioned;
import dev.trakr.contracts.events.IssueUpdated;
import dev.trakr.contracts.events.WorkLogged;
import dev.trakr.kernel.Rank;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    /** 09-security §4: description HTML up to 200 kB. */
    private static final int MAX_DESCRIPTION_HTML = 200 * 1024;
    private static final Duration DESCRIPTION_HISTORY_WINDOW = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(IssueService.class);

    private final IssueRepository issues;
    private final IssueLinkRepository links;
    private final WorklogRepository worklogs;
    private final IssueQueries queries;
    private final IssueHistory history;
    private final IssueMarks marks;
    private final IssueNumberAllocator numbers;
    private final IssueLocks locks;
    private final ProjectAccess access;
    private final WorkflowService workflow;
    private final BoardService boards;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;
    // phase 18: trakr.issue.moves{project} — how hot the board is, per project (Prometheus / OTLP)
    private final MeterRegistry meters;

    IssueService(IssueRepository issues, IssueLinkRepository links, WorklogRepository worklogs, IssueQueries queries,
            IssueHistory history, IssueMarks marks, IssueNumberAllocator numbers, IssueLocks locks,
            ProjectAccess access, WorkflowService workflow, BoardService boards, ApplicationEventPublisher events,
            TransactionTemplate transactions, Clock clock, MeterRegistry meters) {
        this.issues = issues;
        this.links = links;
        this.worklogs = worklogs;
        this.queries = queries;
        this.history = history;
        this.marks = marks;
        this.numbers = numbers;
        this.locks = locks;
        this.access = access;
        this.workflow = workflow;
        this.boards = boards;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        this.meters = meters;
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

    /**
     * The hot path (01-architecture §5.1): a card dropped on the board or in the backlog. One row
     * changes — status, sprint and rank of this issue — and the rank is computed here from the
     * neighbours' <em>current</em> ranks, not taken from the client.
     */
    @Transactional
    public MoveResult move(UUID userId, String key, MoveIssue cmd) {
        // BLOCK 1: load + lock — the project's rank lock first (same order as create), then the issue row
        Grant grant = grant(userId, key, ProjectRole.MEMBER);
        locks.lockRanks(grant.projectId());
        UUID issueId = locks.lockIssue(grant.workspaceId(), key).orElseThrow(() -> notFound(key));
        Issue issue = issues.findById(issueId).orElseThrow(() -> notFound(key));
        requireVersion(cmd.expectedVersion(), issue, grant, userId);
        List<String> warnings = new ArrayList<>();
        UUID fromSprintId = issue.sprintId();
        String fromRank = issue.rank();

        // BLOCK 2: status change? The workflow decides; the issue sets resolvedAt
        WorkflowStatusView fromStatus = null;
        WorkflowStatusView toStatus = null;
        if (cmd.toStatusId() != null && !cmd.toStatusId().equals(issue.statusId())) {
            toStatus = workflow.requireTransition(grant.projectId(), issue.statusId(), cmd.toStatusId());
            fromStatus = workflow.status(grant.projectId(), issue.statusId());
            issue.transitionTo(toStatus, clock);
        }

        // BLOCK 3: sprint change? Absent = stay, null = backlog
        UUID toSprintId = fromSprintId;
        if (cmd.toSprintId() != null) {
            toSprintId = cmd.toSprintId().value();
            if (toSprintId != null) {
                requireOpenSprint(grant, toSprintId);
            }
        }
        boolean sprintChanged = !Objects.equals(fromSprintId, toSprintId);

        // BLOCK 4: rank, from the neighbours as they are now (we hold the lock, so they can't change under us)
        String rank = rankBetween(grant, issue, toSprintId, cmd.beforeKey(), cmd.afterKey(), sprintChanged, warnings);
        boolean moved = issue.moveTo(toSprintId, rank, clock);
        if (!moved && toStatus == null) {
            // dropped where it already was: nothing changed, nothing to tell anyone
            return new MoveResult(detailOf(grant, key, userId), warnings);
        }
        issues.flush();

        // BLOCK 5: WIP check — advisory (00-product §4.4): the move stands, the response warns
        if (toStatus != null || sprintChanged) {
            wipExceeded(grant, issue).ifPresent(warnings::add);
        }

        // BLOCK 6: history + events. Both events carry the same sequence (one version bump); dedupe by event id
        Counter.builder("trakr.issue.moves").description("Cards moved on a board or in a backlog")
                .tag("project", grant.key()).register(meters).increment();
        history.moved(issue, userId, fromSprintId, fromRank);
        IssueSnapshot snapshot = issue.snapshot();
        events.publishEvent(IssueMoved.of(clock.instant(), userId, fromSprintId, snapshot));
        if (toStatus != null && fromStatus != null) {
            history.transitioned(issue, userId, fromStatus, toStatus);
            events.publishEvent(IssueTransitioned.of(clock.instant(), userId, fromStatus.id(), snapshot));
        }
        return new MoveResult(detailOf(grant, key, userId), warnings);
    }

    /**
     * Moves a sprint's issues to the backlog ({@code toSprintId} null; they go to its <em>top</em>, in
     * their sprint order) or to the bottom of another sprint. For the sprint module — completing a sprint
     * (open issues only) or deleting one (all) — inside its transaction, after it checked access.
     *
     * @return the ids of the moved issues, in their order
     */
    @Transactional
    public List<UUID> moveSprintIssues(UUID actorId, UUID projectId, UUID fromSprintId, @Nullable UUID toSprintId,
            boolean openOnly) {
        locks.lockRanks(projectId);
        List<Issue> moving = issues.findBySprintIdAndDeletedAtIsNullOrderByRank(fromSprintId).stream()
                .filter(issue -> !openOnly || !"done".equals(issue.statusCategory()))
                .toList();
        if (!openOnly) {
            issues.detachDeletedFromSprint(fromSprintId);
        }
        if (moving.isEmpty()) {
            return List.of();
        }
        // nBetween spreads them evenly in the gap and keeps their order
        List<String> ranks = toSprintId == null
                ? Rank.nBetween(null, queries.firstRank(projectId, null).orElse(null), moving.size())
                : Rank.nBetween(queries.lastRank(projectId, toSprintId).orElse(null), null, moving.size());
        List<String> fromRanks = moving.stream().map(Issue::rank).toList();
        for (int i = 0; i < moving.size(); i++) {
            moving.get(i).moveTo(toSprintId, ranks.get(i), clock);
        }
        issues.flush();
        for (int i = 0; i < moving.size(); i++) {
            Issue issue = moving.get(i);
            history.moved(issue, actorId, fromSprintId, fromRanks.get(i));
            events.publishEvent(IssueMoved.of(clock.instant(), actorId, fromSprintId, issue.snapshot()));
        }
        return moving.stream().map(Issue::id).toList();
    }

    /**
     * One action on up to 100 issues, each in its own transaction: one issue failing (no permission, a
     * transition the workflow refuses) doesn't undo the others, and the answer says which did what.
     * Not @Transactional itself — TransactionTemplate opens one transaction per key.
     */
    public List<BulkCommand.Result> bulk(UUID userId, BulkCommand cmd) {
        List<BulkCommand.Result> results = new ArrayList<>();
        for (String key : new LinkedHashSet<>(cmd.keys())) {
            try {
                results.add(transactions.execute(status -> bulkOne(userId, key, cmd)));
            } catch (TrakrException e) {
                results.add(BulkCommand.Result.failed(key, e.code(), e.getMessage()));
            } catch (OptimisticLockingFailureException e) {
                results.add(BulkCommand.Result.failed(key, "VERSION_MISMATCH", "Changed by someone else meanwhile."));
            }
        }
        return results;
    }

    // runs inside the per-key transaction; calls on "this" skip the @Transactional proxy, and the template's
    // transaction covers them instead
    private BulkCommand.Result bulkOne(UUID userId, String key, BulkCommand cmd) {
        long version = load(grant(userId, key, ProjectRole.MEMBER), key).version();
        return switch (cmd.action()) {
            case TRANSITION -> {
                if (cmd.statusId() == null) {
                    throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Transition needs statusId.");
                }
                yield BulkCommand.Result.ok(key,
                        transition(userId, key, new TransitionIssue(cmd.statusId(), version)).version());
            }
            case ASSIGN -> BulkCommand.Result.ok(key,
                    patch(userId, key, version, PatchIssue.none().withAssignee(cmd.assigneeId())).version());
            case MOVE_TO_SPRINT -> BulkCommand.Result.ok(key, move(userId, key,
                    new MoveIssue(null, new PatchIssue.Value<>(cmd.sprintId()), null, null, version)).issue().version());
            case DELETE -> {
                delete(userId, key, version);
                yield BulkCommand.Result.ok(key, null);
            }
        };
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

    // BLOCK: for the comment and attachment modules — the access check they need, and the history they add to

    /** The issue behind a key, if the caller has at least {@code minimum} in its project; 404 otherwise. */
    @Transactional(readOnly = true)
    public IssueHandle require(UUID userId, String key, ProjectRole minimum) {
        Grant grant = grant(userId, key, minimum);
        return handle(grant, load(grant, key));
    }

    /** The same for things that store only the issue id (a comment, an attachment). */
    @Transactional(readOnly = true)
    public IssueHandle require(UUID userId, UUID issueId, ProjectRole minimum) {
        Issue issue = issues.findById(issueId).filter(candidate -> !candidate.isDeleted())
                .orElseThrow(() -> new TrakrException.NotFound("No such issue."));
        return handle(grant(userId, issue.key(), minimum), issue);
    }

    /**
     * A comment was added: a history row, so "Worked on" and the activity feed see it. Not a change to the
     * issue itself — its version stays, so an open issue page's If-Match is still good.
     */
    @Transactional
    public void recordComment(UUID issueId, UUID actorId, UUID commentId) {
        issues.findById(issueId).ifPresent(issue -> history.commented(issue, actorId, commentId));
    }

    // BLOCK: collaborative editing (phase 16) — the description from a collab snapshot

    /**
     * Stores the description a collab snapshot carries (06-collab §4 step 5), called by DescriptionSnapshotConsumer.
     * The HTML comes from a browser, so it is sanitised with the same allow-list as comments, and the text is taken
     * from the sanitised HTML rather than from the snapshot's own text: what search finds is exactly what the page
     * shows. Unchanged → nothing written, no version bump, no event.
     *
     * <p>No access check: collab checked the user's role when the socket opened, and only members are ever asked
     * for snapshots. Not a REST path, so no If-Match either — the CRDT already merged everyone's edits.
     */
    @Transactional
    public void applyDescriptionSnapshot(UUID issueId, UUID actorId, String html) {
        Issue issue = issues.findById(issueId).filter(candidate -> !candidate.isDeleted()).orElse(null);
        if (issue == null) {
            return;
        }
        String sanitized = HtmlSanitizer.sanitize(html);
        String text = HtmlSanitizer.text(sanitized);
        if (sanitized.length() > MAX_DESCRIPTION_HTML) {
            log.warn("description snapshot for {} is {} characters after sanitising; not stored", issue.key(),
                    sanitized.length());
            return;
        }
        // an emptied editor still produces "<p></p>": no text means no description
        String newHtml = text.isEmpty() ? null : sanitized;
        String newText = text.isEmpty() ? null : text;
        String oldText = issue.descriptionText();
        if (!issue.replaceDescription(newHtml, newText, clock)) {
            return;
        }
        issues.flush();
        history.descriptionEdited(issue, actorId, oldText, newText, DESCRIPTION_HISTORY_WINDOW);
        events.publishEvent(IssueDescriptionChanged.of(clock.instant(), actorId, newText, newHtml, issue.snapshot()));
    }

    private static IssueHandle handle(Grant grant, Issue issue) {
        return new IssueHandle(issue.id(), issue.key(), grant.projectId(), grant.workspaceId(), grant.role());
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

    /**
     * A rank strictly between the requested neighbours and unique in the destination list. The new rank
     * goes between the lower bound and the <em>next existing rank</em> above it, so even when another move
     * landed between the same two neighbours a moment ago, ours lands in the gap that is left.
     * Neighbours missing or out of order: the end of the list, plus NEIGHBOURS_CHANGED.
     */
    private String rankBetween(Grant grant, Issue issue, @Nullable UUID sprintId, @Nullable String beforeKey,
            @Nullable String afterKey, boolean sprintChanged, List<String> warnings) {
        UUID projectId = grant.projectId();
        if (beforeKey == null && afterKey == null) {
            // no position given: keep it within the same list, append when it changes lists
            return sprintChanged ? endOf(projectId, sprintId, issue) : issue.rank();
        }
        String before = beforeKey == null ? null
                : queries.rankInList(projectId, sprintId, beforeKey, issue.id()).orElse(null);
        String after = afterKey == null ? null
                : queries.rankInList(projectId, sprintId, afterKey, issue.id()).orElse(null);
        boolean missing = (beforeKey != null && before == null) || (afterKey != null && after == null);
        if (missing || (before != null && after != null && before.compareTo(after) >= 0)) {
            warnings.add("NEIGHBOURS_CHANGED");
            return endOf(projectId, sprintId, issue);
        }
        if (before != null) {
            String next = queries.rankAbove(projectId, sprintId, before, issue.id()).orElse(null);
            return Rank.between(before, next);
        }
        String previous = queries.rankBelow(projectId, sprintId, after, issue.id()).orElse(null);
        return Rank.between(previous, after);
    }

    private String endOf(UUID projectId, @Nullable UUID sprintId, Issue issue) {
        return Rank.between(queries.rankBelow(projectId, sprintId, null, issue.id()).orElse(null), null);
    }

    // scrum: counts the issue's sprint (a backlog issue is on no board); kanban: the whole project
    private Optional<String> wipExceeded(Grant grant, Issue issue) {
        BoardColumnView column = boards.columnFor(grant.projectId(), issue.statusId()).orElse(null);
        if (column == null || column.wipLimit() == null) {
            return Optional.empty();
        }
        boolean kanban = "kanban".equals(column.boardType());
        if (!kanban && issue.sprintId() == null) {
            return Optional.empty();
        }
        int count = queries.countInStatuses(grant.projectId(), column.statusIds(), kanban ? null : issue.sprintId());
        return count > column.wipLimit() ? Optional.of("WIP_LIMIT_EXCEEDED") : Optional.empty();
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

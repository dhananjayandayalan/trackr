package dev.trakr.api.workflow;

import dev.trakr.api.project.ProjectAccess;
import dev.trakr.api.project.ProjectAccess.Grant;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.api.workflow.WorkflowReplacement.StatusDraft;
import dev.trakr.api.workflow.WorkflowView.TransitionView;
import dev.trakr.api.workflow.internal.WorkflowStatus;
import dev.trakr.api.workflow.internal.WorkflowStatusRepository;
import dev.trakr.api.workflow.internal.WorkflowStore;
import dev.trakr.api.workflow.internal.WorkflowTransitionRepository;
import dev.trakr.contracts.events.WorkflowChanged;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workflow module's public API: a project's statuses, and which moves between them are allowed.
 * Issues ask here before every status change; the rules live with the workflow, not with the issue.
 */
@Service
public class WorkflowService {

    static final int MAX_STATUSES = 20;
    private static final Set<String> CATEGORIES = Set.of("todo", "in_progress", "done");
    // the VOLT status tokens (--status-todo, …) a status may be drawn in
    private static final Set<String> COLORS = Set.of("todo", "progress", "review", "done", "blocked");

    private final WorkflowStatusRepository statuses;
    private final WorkflowTransitionRepository transitions;
    private final WorkflowStore store;
    private final ProjectAccess access;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    WorkflowService(WorkflowStatusRepository statuses, WorkflowTransitionRepository transitions, WorkflowStore store,
            ProjectAccess access, ApplicationEventPublisher events, Clock clock) {
        this.statuses = statuses;
        this.transitions = transitions;
        this.store = store;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    // JPA on purpose, not jOOQ: the board seeder calls this in the transaction that just saved the statuses.
    // A JPQL query first flushes pending INSERTs of the entities it reads; a jOOQ query would see nothing.
    @Transactional(readOnly = true)
    public List<WorkflowStatusView> statusesOf(UUID projectId) {
        return statuses.findByProjectIdOrderByPosition(projectId).stream().map(WorkflowStatus::toView).toList();
    }

    /** A status of this project; 400 when the id belongs to no status here (another project's, or made up). */
    @Transactional(readOnly = true)
    public WorkflowStatusView status(UUID projectId, UUID statusId) {
        return statuses.findByIdAndProjectId(statusId, projectId).map(WorkflowStatus::toView)
                .orElseThrow(() -> new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "No such status in this project.", List.of(new FieldMessage("toStatusId", "is not a status of this project"))));
    }

    /** Where new issues start: the first {@code todo} status by position. */
    @Transactional(readOnly = true)
    public WorkflowStatusView initialStatus(UUID projectId) {
        return statuses.findFirstByProjectIdAndCategoryOrderByPosition(projectId, "todo").map(WorkflowStatus::toView)
                .orElseThrow(() -> new TrakrException.Conflict("NO_INITIAL_STATUS",
                        "The project's workflow has no status in the To do category."));
    }

    /**
     * The target status if the workflow allows {@code from → to}; else 409 TRANSITION_NOT_ALLOWED.
     * A transition with no from-status means "from any status".
     */
    @Transactional(readOnly = true)
    public WorkflowStatusView requireTransition(UUID projectId, UUID fromStatusId, UUID toStatusId) {
        WorkflowStatusView to = status(projectId, toStatusId);
        if (!transitions.isAllowed(projectId, fromStatusId, toStatusId)) {
            throw new TransitionNotAllowed(status(projectId, fromStatusId).name(), to.name());
        }
        return to;
    }

    /** The statuses an issue in {@code fromStatusId} may move to, in workflow order (GET /issues/{key}/transitions). */
    @Transactional(readOnly = true)
    public List<WorkflowStatusView> allowedTargets(UUID projectId, UUID fromStatusId) {
        return statuses.findTargets(projectId, fromStatusId).stream().map(WorkflowStatus::toView).toList();
    }

    /** GET /projects/{key}/workflow (phase 17). */
    @Transactional(readOnly = true)
    public WorkflowView view(UUID userId, String projectKey) {
        return view(access.require(userId, projectKey, ProjectRole.VIEWER).projectId());
    }

    private WorkflowView view(UUID projectId) {
        return new WorkflowView(store.statuses(projectId), store.transitions(projectId), store.version(projectId));
    }

    /**
     * PUT /projects/{key}/workflow: replaces statuses and transitions in one transaction (03-api §3.3).
     *
     * <p>Order matters, because of the foreign keys: version first (the lock), then the transitions go, the
     * statuses are written, {@link WorkflowReplaced} lets the issue and board modules move off the deleted
     * statuses, and only then are those deleted and the new transitions inserted.
     */
    @Transactional
    public WorkflowView replace(UUID userId, String projectKey, long expectedVersion, WorkflowReplacement replacement) {
        // BLOCK 1: authorize, then the optimistic lock — before reading anything we'd compare against
        Grant grant = access.require(userId, projectKey, ProjectRole.ADMIN);
        UUID projectId = grant.projectId();
        if (expectedVersion != store.version(projectId) || !store.bumpVersion(projectId, expectedVersion)) {
            throw new TrakrException.VersionMismatch(view(projectId));
        }

        // BLOCK 2: validate the new shape against the current one
        Map<UUID, WorkflowStatusView> current = store.statuses(projectId).stream()
                .collect(Collectors.toMap(WorkflowStatusView::id, status -> status));
        List<WorkflowStatusView> next = validStatuses(replacement.statuses());
        Set<UUID> nextIds = next.stream().map(WorkflowStatusView::id).collect(Collectors.toSet());
        Set<UUID> added = new HashSet<>(nextIds);
        added.removeAll(current.keySet());
        if (!added.isEmpty() && !store.takenElsewhere(projectId, added).isEmpty()) {
            throw invalid("statuses", "a new status needs an id of its own");
        }
        Set<UUID> deleted = new HashSet<>(current.keySet());
        deleted.removeAll(nextIds);
        Map<UUID, Integer> counts = deleted.isEmpty() ? Map.of() : store.issueCounts(deleted);
        Map<UUID, UUID> remapTo = validRemap(replacement.remapTo(), deleted, nextIds, counts, current);
        List<TransitionView> nextTransitions = validTransitions(replacement.transitions(), nextIds);

        // BLOCK 3: write — transitions out, statuses in, dependents follow, deleted statuses out, transitions in
        store.deleteTransitions(projectId);
        store.parkNames(projectId);
        Map<UUID, String> previousNames = new HashMap<>();
        for (WorkflowStatusView status : next) {
            WorkflowStatusView before = current.get(status.id());
            if (before == null) {
                store.insert(projectId, status);
            } else {
                store.update(status);
                if (!before.name().equals(status.name())) {
                    previousNames.put(status.id(), before.name());
                }
            }
        }
        events.publishEvent(new WorkflowReplaced(projectId, next, remapTo, added, deleted, previousNames));
        store.deleteStatuses(deleted);
        store.insertTransitions(projectId, nextTransitions);

        // BLOCK 4: tell the other apps (search index names, burndown categories) and the browsers
        WorkflowView saved = view(projectId);
        events.publishEvent(WorkflowChanged.of(clock.instant(), userId, grant.workspaceId(), projectId, saved.version(),
                saved.statuses().stream().map(s -> new WorkflowChanged.Status(s.id(), s.name(), s.category(),
                        s.position(), s.color(), s.wipLimit())).toList(),
                saved.transitions().stream()
                        .map(t -> new WorkflowChanged.Transition(t.fromStatusId(), t.toStatusId())).toList(),
                remapTo));
        return saved;
    }

    // BLOCK 5: validation — every rule gives a field-level message, so the editor can point at the row
    private static List<WorkflowStatusView> validStatuses(List<StatusDraft> drafts) {
        List<FieldMessage> errors = new ArrayList<>();
        if (drafts.isEmpty() || drafts.size() > MAX_STATUSES) {
            errors.add(new FieldMessage("statuses", "must have 1 to " + MAX_STATUSES + " statuses"));
        }
        Set<String> names = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        List<WorkflowStatusView> result = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            StatusDraft draft = drafts.get(i);
            String field = "statuses[" + i + "]";
            String name = draft.name() == null ? "" : draft.name().strip();
            if (name.isEmpty() || name.length() > 30) {
                errors.add(new FieldMessage(field + ".name", "must be 1 to 30 characters"));
            } else if (!names.add(name.toLowerCase(Locale.ROOT))) {
                errors.add(new FieldMessage(field + ".name", "is used twice"));
            }
            if (draft.id() == null || !ids.add(draft.id())) {
                errors.add(new FieldMessage(field + ".id", "must be a unique id"));
            }
            if (!CATEGORIES.contains(draft.category())) {
                errors.add(new FieldMessage(field + ".category", "must be todo, in_progress or done"));
            }
            if (draft.color() != null && !COLORS.contains(draft.color())) {
                errors.add(new FieldMessage(field + ".color", "must be one of todo, progress, review, done, blocked"));
            }
            if (draft.wipLimit() != null && (draft.wipLimit() < 1 || draft.wipLimit() > 999)) {
                errors.add(new FieldMessage(field + ".wipLimit", "must be between 1 and 999"));
            }
            String color = draft.color() != null ? draft.color() : switch (String.valueOf(draft.category())) {
                case "in_progress" -> "progress";
                case "done" -> "done";
                default -> "todo";
            };
            result.add(new WorkflowStatusView(draft.id(), name, draft.category(), i, color, draft.wipLimit()));
        }
        // new issues start in the first To do status (initialStatus)
        if (drafts.stream().noneMatch(draft -> "todo".equals(draft.category()))) {
            errors.add(new FieldMessage("statuses", "need at least one status in the To do category"));
        }
        if (!errors.isEmpty()) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The workflow is not valid.", errors);
        }
        return result;
    }

    /** Deleted statuses with issues need somewhere to send them: 409 STATUS_IN_USE names each one that doesn't. */
    private static Map<UUID, UUID> validRemap(Map<UUID, UUID> requested, Set<UUID> deleted, Set<UUID> kept,
            Map<UUID, Integer> issueCounts, Map<UUID, WorkflowStatusView> current) {
        Map<UUID, UUID> remap = new HashMap<>();
        List<FieldMessage> missing = new ArrayList<>();
        for (UUID id : deleted) {
            UUID target = requested.get(id);
            if (target != null && !kept.contains(target)) {
                throw invalid("remapTo." + id, "must be a status that stays");
            }
            if (target != null) {
                remap.put(id, target);
            } else if (issueCounts.getOrDefault(id, 0) > 0) {
                missing.add(new FieldMessage("remapTo." + id, current.get(id).name() + " has "
                        + issueCounts.get(id) + " issues; choose a status to move them to"));
            }
        }
        if (!missing.isEmpty()) {
            throw new TrakrException(HttpStatus.CONFLICT, "STATUS_IN_USE",
                    "Statuses that still have issues can only be deleted with a status to move the issues to.", missing);
        }
        return remap;
    }

    private static List<TransitionView> validTransitions(List<TransitionView> requested, Set<UUID> statusIds) {
        Set<TransitionView> unique = new LinkedHashSet<>();
        for (TransitionView transition : requested) {
            boolean fromOk = transition.fromStatusId() == null || statusIds.contains(transition.fromStatusId());
            if (!fromOk || transition.toStatusId() == null || !statusIds.contains(transition.toStatusId())) {
                throw invalid("transitions", "must connect statuses of this workflow");
            }
            if (!transition.toStatusId().equals(transition.fromStatusId())) {
                unique.add(transition);
            }
        }
        return List.copyOf(unique);
    }

    private static TrakrException invalid(String field, String message) {
        return new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The workflow is not valid.",
                List.of(new FieldMessage(field, message)));
    }
}

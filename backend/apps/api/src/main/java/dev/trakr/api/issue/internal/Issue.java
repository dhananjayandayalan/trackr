package dev.trakr.api.issue.internal;

import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.InvalidParent;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.PatchIssue;
import dev.trakr.api.issue.PatchIssue.Value;
import dev.trakr.api.issue.Priority;
import dev.trakr.api.workflow.WorkflowStatusView;
import dev.trakr.contracts.events.IssueSnapshot;
import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * The issue aggregate: every change to an issue goes through a method here, and each method keeps
 * the rules true (a subtask has a parent, resolvedAt matches the status category, …). The service
 * around it does what needs other modules or the database: access, workflow rules, numbers, ranks.
 */
@Entity
@Table(name = "issue")
public class Issue {

    // BLOCK 1: mapping
    @Id
    private UUID id;
    private UUID workspaceId;
    private UUID projectId;
    private long number;
    private String key;
    private String type;
    private String summary;
    private @Nullable String descriptionHtml;
    private @Nullable String descriptionText;
    private UUID statusId;
    private String statusCategory;
    private String priority;
    private @Nullable UUID assigneeId;
    private UUID reporterId;
    private @Nullable UUID parentId;
    private @Nullable UUID sprintId;
    private @Nullable BigDecimal storyPoints;
    private String rank;
    // Postgres text[]: Hibernate binds the list as one array value
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> labels;
    private @Nullable LocalDate dueDate;
    private @Nullable LocalDate startDate;
    private boolean flagged;
    private @Nullable Integer originalEstimateMin;
    private int timeSpentMin;
    private @Nullable String fixVersion;
    // jsonb: Hibernate turns the map into JSON and back with Jackson
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> customFields;
    private @Nullable Instant resolvedAt;
    private @Nullable Instant deletedAt;
    // bumped by every UPDATE Hibernate writes, which happens only when a field really changed (dirty checking).
    // A no-op patch writes nothing, keeps the version, and must not publish an event either.
    @Version
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Issue() {
        // for JPA
    }

    // BLOCK 2: factory
    /** A new issue at version 0; {@code parent} is the resolved {@code cmd.parentKey()}, or null. */
    public static Issue create(CreateIssue cmd, UUID workspaceId, UUID projectId, long number, String key,
            WorkflowStatusView initialStatus, String rank, UUID reporterId, @Nullable Parent parent, Clock clock) {
        Issue issue = new Issue();
        issue.id = Ids.newId();
        issue.workspaceId = workspaceId;
        issue.projectId = projectId;
        issue.number = number;
        issue.key = key;
        issue.requireValidRelatives(cmd.type(), parent, Set.of());
        issue.type = cmd.type().value();
        issue.summary = cmd.summary().strip();
        issue.setDescription(cmd.description());
        issue.statusId = initialStatus.id();
        issue.statusCategory = initialStatus.category();
        issue.priority = (cmd.priority() == null ? Priority.MEDIUM : cmd.priority()).value();
        issue.assigneeId = cmd.assigneeId();
        issue.reporterId = reporterId;
        issue.parentId = parent == null ? null : parent.id();
        issue.sprintId = cmd.sprintId();
        issue.storyPoints = points(cmd.storyPoints());
        issue.rank = rank;
        issue.labels = normalizedLabels(cmd.labels());
        issue.dueDate = cmd.dueDate();
        issue.customFields = Map.of();
        issue.createdAt = clock.instant();
        issue.updatedAt = issue.createdAt;
        if ("done".equals(issue.statusCategory)) {
            issue.resolvedAt = issue.createdAt;
        }
        return issue;
    }

    // BLOCK 3: transitionTo(status)
    /**
     * Moves to {@code status} (the workflow already allowed it). Entering a done-category status sets
     * resolvedAt, leaving one clears it; Done → Closed keeps it. False when it's the current status.
     */
    public boolean transitionTo(WorkflowStatusView status, Clock clock) {
        if (status.id().equals(statusId)) {
            return false;
        }
        boolean wasDone = "done".equals(statusCategory);
        boolean isDone = "done".equals(status.category());
        statusId = status.id();
        statusCategory = status.category();
        if (isDone && !wasDone) {
            resolvedAt = clock.instant();
        } else if (!isDone) {
            resolvedAt = null;
        }
        touch(clock);
        return true;
    }

    // BLOCK 4: apply(PatchIssue)
    /**
     * Applies the fields present in {@code patch} and returns exactly what changed (setting a field to
     * its current value is not a change). {@code parentAfter} is the parent once the patch is applied,
     * resolved by the service whenever the type or the parent changes; {@code childTypes} are the types
     * of this issue's children, needed when the type changes.
     */
    public List<FieldChange> apply(PatchIssue patch, @Nullable Parent parentAfter, Set<IssueType> childTypes,
            Clock clock) {
        if (patch.type() != null || patch.parentKey() != null) {
            requireValidRelatives(patch.type() == null ? type() : required(patch.type()), parentAfter, childTypes);
        }
        List<FieldChange> changes = new ArrayList<>();

        if (differs(patch.summary(), summary, String::strip)) {
            String newSummary = required(patch.summary()).strip();
            changes.add(new FieldChange("summary", summary, newSummary));
            summary = newSummary;
        }
        if (differs(patch.description(), descriptionText, Issue::descriptionOf)) {
            String newText = patch.description().value();
            changes.add(new FieldChange("description", descriptionText, newText));
            setDescription(newText);
        }
        if (differs(patch.type(), type(), Function.identity())) {
            IssueType newType = required(patch.type());
            changes.add(new FieldChange("type", type, newType.value()));
            type = newType.value();
        }
        if (differs(patch.priority(), priority(), Function.identity())) {
            Priority newPriority = required(patch.priority());
            changes.add(new FieldChange("priority", priority, newPriority.value()));
            priority = newPriority.value();
        }
        if (differs(patch.assigneeId(), assigneeId, Function.identity())) {
            UUID newAssignee = patch.assigneeId().value();
            changes.add(new FieldChange("assignee", text(assigneeId), text(newAssignee)));
            assigneeId = newAssignee;
        }
        UUID newParentId = parentAfter == null ? null : parentAfter.id();
        if (patch.parentKey() != null && !Objects.equals(parentId, newParentId)) {
            changes.add(new FieldChange("parent", text(parentId), text(newParentId)));
            parentId = newParentId;
        }
        if (differs(patch.storyPoints(), storyPoints, Issue::points)) {
            BigDecimal newPoints = points(patch.storyPoints().value());
            changes.add(new FieldChange("storyPoints", storyPoints, newPoints));
            storyPoints = newPoints;
        }
        if (differs(patch.labels(), labels, Issue::normalizedLabels)) {
            List<String> newLabels = normalizedLabels(patch.labels().value());
            changes.add(new FieldChange("labels", labels, newLabels));
            labels = newLabels;
        }
        if (differs(patch.dueDate(), dueDate, Function.identity())) {
            LocalDate newDate = patch.dueDate().value();
            changes.add(new FieldChange("dueDate", text(dueDate), text(newDate)));
            dueDate = newDate;
        }
        if (differs(patch.startDate(), startDate, Function.identity())) {
            LocalDate newDate = patch.startDate().value();
            changes.add(new FieldChange("startDate", text(startDate), text(newDate)));
            startDate = newDate;
        }
        if (differs(patch.flagged(), flagged, Function.identity())) {
            boolean newFlagged = Boolean.TRUE.equals(patch.flagged().value());
            changes.add(new FieldChange("flagged", flagged, newFlagged));
            flagged = newFlagged;
        }
        if (differs(patch.fixVersion(), fixVersion, Function.identity())) {
            String newFixVersion = patch.fixVersion().value();
            changes.add(new FieldChange("fixVersion", fixVersion, newFixVersion));
            fixVersion = newFixVersion;
        }
        if (differs(patch.originalEstimateMin(), originalEstimateMin, Function.identity())) {
            Integer newEstimate = patch.originalEstimateMin().value();
            changes.add(new FieldChange("originalEstimateMin", originalEstimateMin, newEstimate));
            originalEstimateMin = newEstimate;
        }

        if (!changes.isEmpty()) {
            touch(clock);
        }
        return List.copyOf(changes);
    }

    // BLOCK 5: softDelete(clock)
    /** False when it was deleted already. */
    public boolean softDelete(Clock clock) {
        if (deletedAt != null) {
            return false;
        }
        deletedAt = clock.instant();
        touch(clock);
        return true;
    }

    /**
     * Puts the issue in another list ({@code sprintId} null = backlog) at {@code rank}. The service
     * computes the rank from the neighbours; the issue only records where it ended up. False when
     * neither changed.
     */
    public boolean moveTo(@Nullable UUID newSprintId, String newRank, Clock clock) {
        if (Objects.equals(sprintId, newSprintId) && rank.equals(newRank)) {
            return false;
        }
        sprintId = newSprintId;
        rank = newRank;
        touch(clock);
        return true;
    }

    /** Adds logged time; the worklog row itself is a separate entity. */
    public void logWork(int minutes, Clock clock) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("minutes must be positive: " + minutes);
        }
        timeSpentMin += minutes;
        touch(clock);
    }

    /**
     * Records that something belonging to the issue but stored elsewhere changed (a link). The new
     * updatedAt makes the row dirty, so the version goes up: the ETag changes and the event gets a
     * fresh sequence.
     */
    public void touch(Clock clock) {
        updatedAt = clock.instant();
    }

    // BLOCK 6: snapshot()
    public IssueSnapshot snapshot() {
        return new IssueSnapshot(id, workspaceId, projectId, projectKey(), key, number, type, summary, descriptionText,
                statusId, statusCategory, priority, assigneeId, reporterId, parentId, sprintId, storyPoints, rank,
                labels, dueDate, startDate, flagged, originalEstimateMin, timeSpentMin, fixVersion, resolvedAt,
                deletedAt != null, version, createdAt, updatedAt);
    }

    // 00-product §4.2, checked for the type and parent the issue will have
    private void requireValidRelatives(IssueType newType, @Nullable Parent parent, Set<IssueType> childTypes) {
        if (parent != null) {
            if (parent.id().equals(id)) {
                throw new InvalidParent("An issue cannot be its own parent.");
            }
            if (!parent.projectId().equals(projectId)) {
                throw new InvalidParent("The parent must be in the same project.");
            }
        }
        switch (newType) {
            case EPIC -> {
                if (parent != null) {
                    throw new InvalidParent("An epic cannot have a parent.");
                }
            }
            case SUBTASK -> {
                if (parent == null) {
                    throw new InvalidParent("A subtask needs a parent.");
                }
                if (parent.type() == IssueType.SUBTASK) {
                    throw new InvalidParent("A subtask's parent cannot be another subtask.");
                }
            }
            case STORY, BUG, TASK -> {
                if (parent != null && parent.type() != IssueType.EPIC) {
                    throw new InvalidParent("Only an epic can be the parent of a " + newType.value() + ".");
                }
            }
        }
        // the children must still be valid under the new type
        if (newType == IssueType.SUBTASK && childTypes.contains(IssueType.SUBTASK)) {
            throw new InvalidParent("An issue with subtasks cannot become a subtask.");
        }
        if (newType != IssueType.EPIC && childTypes.stream().anyMatch(child -> child != IssueType.SUBTASK)) {
            throw new InvalidParent("Only an epic can have stories, bugs or tasks as children.");
        }
    }

    /**
     * The description from a collaborative-editing snapshot (phase 16): HTML the api already sanitised, and the text
     * of that HTML. Returns false when both equal what is stored — then nothing is written and the version stays.
     */
    public boolean replaceDescription(@Nullable String html, @Nullable String text, Clock clock) {
        if (Objects.equals(html, descriptionHtml) && Objects.equals(text, descriptionText)) {
            return false;
        }
        descriptionHtml = html;
        descriptionText = text;
        touch(clock);
        return true;
    }

    public @Nullable String descriptionText() {
        return descriptionText;
    }

    // a description from the create dialog or PATCH arrives as plain text; the collaborative editor sends HTML
    private void setDescription(@Nullable String text) {
        descriptionText = descriptionOf(text);
        descriptionHtml = descriptionText == null ? null : PlainTextHtml.of(descriptionText);
    }

    private static @Nullable String descriptionOf(@Nullable String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static <T> boolean differs(@Nullable Value<T> value, @Nullable Object current,
            Function<T, ?> normalize) {
        if (value == null) {
            return false;
        }
        Object proposed = value.value() == null ? null : normalize.apply(value.value());
        return !Objects.equals(proposed, current);
    }

    private static <T> T required(Value<T> value) {
        return Objects.requireNonNull(value.value(), "this field cannot be cleared");
    }

    // numeric(4,1) comes back with scale 1; make ours match, or 3 and 3.0 would count as a change
    private static @Nullable BigDecimal points(@Nullable BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }

    // trimmed, no blanks, no duplicates, first occurrence's order kept
    private static List<String> normalizedLabels(@Nullable List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        values.stream().map(String::strip).filter(label -> !label.isEmpty()).forEach(unique::add);
        return List.copyOf(unique);
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    public UUID id() {
        return id;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID projectId() {
        return projectId;
    }

    public String key() {
        return key;
    }

    public String projectKey() {
        return key.substring(0, key.lastIndexOf('-'));
    }

    public IssueType type() {
        return IssueType.fromValue(type);
    }

    public String summary() {
        return summary;
    }

    public Priority priority() {
        return Priority.fromValue(priority);
    }

    public UUID statusId() {
        return statusId;
    }

    public String statusCategory() {
        return statusCategory;
    }

    public @Nullable UUID assigneeId() {
        return assigneeId;
    }

    public UUID reporterId() {
        return reporterId;
    }

    public @Nullable UUID parentId() {
        return parentId;
    }

    public @Nullable UUID sprintId() {
        return sprintId;
    }

    public String rank() {
        return rank;
    }

    public @Nullable Instant resolvedAt() {
        return resolvedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public long version() {
        return version;
    }
}

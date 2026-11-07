package dev.trakr.api.sprint.internal;

import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.api.sprint.SprintState;
import dev.trakr.api.sprint.SprintView;
import dev.trakr.contracts.events.SprintSnapshot;
import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * A sprint and its lifecycle — a small state machine: PLANNED → ACTIVE → CLOSED. Each method checks
 * the state it starts from, so an illegal step (completing a planned sprint, starting a closed one)
 * can't happen, whoever calls it. "At most one active sprint per project" spans many rows, so this
 * class can't check it: the partial unique index uq_sprint_one_active does.
 */
@Entity
@Table(name = "sprint")
public class Sprint {

    // BLOCK 1: mapping + states
    @Id
    private UUID id;
    private UUID projectId;
    private String name;
    private @Nullable String goal;
    // stored lowercase ("planned"), like every enumeration here
    private String state;
    private @Nullable LocalDate startsOn;
    private @Nullable LocalDate endsOn;
    private int position;
    private @Nullable Instant startedAt;
    private @Nullable Instant completedAt;
    private @Nullable BigDecimal committedPoints;
    private UUID @Nullable [] committedIssueIds;
    @Version
    private long version;
    private Instant createdAt;

    protected Sprint() {
        // for JPA
    }

    public static Sprint plan(UUID projectId, String name, int position, Clock clock) {
        Sprint sprint = new Sprint();
        sprint.id = Ids.newId();
        sprint.projectId = projectId;
        sprint.name = name;
        sprint.state = SprintState.PLANNED.value();
        sprint.position = position;
        sprint.createdAt = clock.instant();
        return sprint;
    }

    // BLOCK 2: start(name, goal, startsOn, endsOn)
    /** Only from PLANNED. Records the commitment: the issues and points in the sprint right now. */
    public void start(String newName, @Nullable String newGoal, LocalDate newStartsOn, LocalDate newEndsOn,
            BigDecimal points, List<UUID> issueIds, Clock clock) {
        requireState(SprintState.PLANNED, "start");
        requireDates(newStartsOn, newEndsOn);
        name = newName;
        goal = newGoal;
        startsOn = newStartsOn;
        endsOn = newEndsOn;
        state = SprintState.ACTIVE.value();
        startedAt = clock.instant();
        committedPoints = points;
        committedIssueIds = issueIds.toArray(UUID[]::new);
    }

    // BLOCK 3: complete(clock)
    /** Only from ACTIVE. */
    public void complete(Clock clock) {
        requireState(SprintState.ACTIVE, "complete");
        state = SprintState.CLOSED.value();
        completedAt = clock.instant();
    }

    // BLOCK 4: rename / updateGoal / updateDates — while PLANNED or ACTIVE; a closed sprint is history
    public boolean rename(String newName) {
        requireOpen();
        boolean changed = !name.equals(newName);
        name = newName;
        return changed;
    }

    public boolean updateGoal(@Nullable String newGoal) {
        requireOpen();
        boolean changed = !Objects.equals(goal, newGoal);
        goal = newGoal;
        return changed;
    }

    /** An active sprint keeps both dates; a planned one may have none, one or both. */
    public boolean updateDates(@Nullable LocalDate newStartsOn, @Nullable LocalDate newEndsOn) {
        requireOpen();
        if (state() == SprintState.ACTIVE && (newStartsOn == null || newEndsOn == null)) {
            throw new InvalidSprintState("An active sprint needs a start and an end date.");
        }
        if (newStartsOn != null && newEndsOn != null) {
            requireDates(newStartsOn, newEndsOn);
        }
        boolean changed = !Objects.equals(startsOn, newStartsOn) || !Objects.equals(endsOn, newEndsOn);
        startsOn = newStartsOn;
        endsOn = newEndsOn;
        return changed;
    }

    /** Only planned sprints can be deleted; an active or closed one has a history worth keeping. */
    public void requireDeletable() {
        requireState(SprintState.PLANNED, "delete");
    }

    private void requireState(SprintState expected, String action) {
        if (state() != expected) {
            throw new InvalidSprintState("Cannot " + action + " a sprint that is " + state + ".");
        }
    }

    private void requireOpen() {
        if (state() == SprintState.CLOSED) {
            throw new InvalidSprintState("A closed sprint can't be changed.");
        }
    }

    private static void requireDates(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "The sprint must end after it starts.",
                    List.of(new FieldMessage("endsOn", "must be after startsOn")));
        }
    }

    public SprintView toView() {
        return new SprintView(id, projectId, name, goal, state(), startsOn, endsOn, position, startedAt, completedAt,
                committedPoints, version);
    }

    public SprintSnapshot snapshot(UUID workspaceId) {
        return new SprintSnapshot(id, workspaceId, projectId, name, goal, state, startsOn, endsOn, position, startedAt,
                completedAt, committedPoints, version);
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public SprintState state() {
        return SprintState.fromValue(state);
    }

    public long version() {
        return version;
    }

    public BigDecimal committedPoints() {
        return committedPoints == null ? BigDecimal.ZERO : committedPoints;
    }

    public List<UUID> committedIssueIds() {
        return committedIssueIds == null ? List.of() : List.of(committedIssueIds);
    }

    /** 409 for a lifecycle step the sprint's state doesn't allow. */
    public static class InvalidSprintState extends TrakrException.Conflict {

        private static final long serialVersionUID = 1L;

        public InvalidSprintState(String detail) {
            super("INVALID_SPRINT_STATE", detail);
        }
    }
}

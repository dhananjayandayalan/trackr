package dev.trakr.api.sprint.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.sprint.SprintState;
import dev.trakr.api.sprint.internal.Sprint.InvalidSprintState;
import dev.trakr.kernel.Ids;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// the sprint lifecycle as plain Java: planned → active → closed, nothing else
class SprintTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate MONDAY = LocalDate.parse("2026-11-09");

    @Test
    void startsFromPlannedAndRecordsTheCommitment() {
        Sprint sprint = planned();
        UUID issue = Ids.newId();

        sprint.start("Sprint 1", "Ship it", MONDAY, MONDAY.plusDays(14), new BigDecimal("13.0"), List.of(issue), CLOCK);

        assertThat(sprint.state()).isEqualTo(SprintState.ACTIVE);
        assertThat(sprint.committedPoints()).isEqualByComparingTo("13");
        assertThat(sprint.committedIssueIds()).containsExactly(issue);
        assertThat(sprint.toView().startedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void startNeedsTheEndAfterTheStart() {
        assertThatThrownBy(() -> planned().start("S", null, MONDAY, MONDAY, BigDecimal.ZERO, List.of(), CLOCK))
                .isInstanceOfSatisfying(TrakrException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void cannotStartTwice() {
        Sprint sprint = active();

        assertThatThrownBy(() -> sprint.start("S", null, MONDAY, MONDAY.plusDays(7), BigDecimal.ZERO, List.of(), CLOCK))
                .isInstanceOf(InvalidSprintState.class);
    }

    @Test
    void completesOnlyFromActive() {
        assertThatThrownBy(() -> planned().complete(CLOCK)).isInstanceOf(InvalidSprintState.class);

        Sprint sprint = active();
        sprint.complete(CLOCK);

        assertThat(sprint.state()).isEqualTo(SprintState.CLOSED);
        assertThat(sprint.toView().completedAt()).isEqualTo(CLOCK.instant());
        assertThatThrownBy(() -> sprint.complete(CLOCK)).isInstanceOf(InvalidSprintState.class);
    }

    @Test
    void closedSprintsCannotBeChanged() {
        Sprint sprint = active();
        sprint.complete(CLOCK);

        assertThatThrownBy(() -> sprint.rename("Renamed")).isInstanceOf(InvalidSprintState.class);
        assertThatThrownBy(() -> sprint.updateGoal("Later")).isInstanceOf(InvalidSprintState.class);
    }

    @Test
    void activeSprintKeepsBothDates() {
        Sprint sprint = active();

        assertThatThrownBy(() -> sprint.updateDates(MONDAY, null)).isInstanceOf(InvalidSprintState.class);
        assertThat(sprint.updateDates(MONDAY, MONDAY.plusDays(21))).isTrue();
        assertThat(sprint.updateDates(MONDAY, MONDAY.plusDays(21))).isFalse();
    }

    @Test
    void plannedSprintMayHaveNoDates() {
        assertThat(planned().updateDates(null, null)).isFalse();
    }

    @Test
    void onlyPlannedSprintsCanBeDeleted() {
        planned().requireDeletable();

        assertThatThrownBy(() -> active().requireDeletable()).isInstanceOf(InvalidSprintState.class);
    }

    @Test
    void renameReportsWhetherAnythingChanged() {
        Sprint sprint = planned();

        assertThat(sprint.rename("MAR Sprint 1")).isFalse();
        assertThat(sprint.rename("Launch")).isTrue();
    }

    private static Sprint planned() {
        return Sprint.plan(Ids.newId(), "MAR Sprint 1", 0, CLOCK);
    }

    private static Sprint active() {
        Sprint sprint = planned();
        sprint.start("MAR Sprint 1", null, MONDAY, MONDAY.plusDays(14), BigDecimal.ZERO, List.of(), CLOCK);
        return sprint;
    }
}

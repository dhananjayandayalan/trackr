package dev.trakr.workers.reports;

import dev.trakr.workers.TestcontainersConfiguration.OwnerJdbc;
import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.contracts.events.IssueSnapshot;
import dev.trakr.contracts.events.IssueTransitioned;
import dev.trakr.contracts.events.SprintEvent.SprintCompleted;
import dev.trakr.contracts.events.SprintEvent.SprintStarted;
import dev.trakr.contracts.events.SprintReport;
import dev.trakr.contracts.events.Topics;
import dev.trakr.workers.Fixtures;
import dev.trakr.workers.Fixtures.World;
import dev.trakr.workers.TestcontainersConfiguration.MutableClock;
import dev.trakr.workers.WorkersIT;
import dev.trakr.workers.kafka.EventPublisher;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Burndown rows across "days" (the test moves the clock) and the sprint report. */
@WorkersIT
class ReportProjectorIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private final EventPublisher publisher;
    private final JdbcClient jdbc;
    private final MutableClock clock;

    @Autowired
    ReportProjectorIT(EventPublisher publisher, OwnerJdbc owner, MutableClock clock) {
        this.publisher = publisher;
        this.jdbc = owner.client();
        this.clock = clock;
    }

    @Test
    void burndownRowsFollowTheSprintDayByDay() {
        World w = Fixtures.world(jdbc);
        IssueSnapshot a = Fixtures.issue(jdbc, w, 1, "A", new BigDecimal("3"), true);
        IssueSnapshot b = Fixtures.issue(jdbc, w, 2, "B", new BigDecimal("5"), true);
        Fixtures.issue(jdbc, w, 3, "Backlog", new BigDecimal("8"), false);

        // day 0: the sprint starts with A + B = 8 points
        clock.set(Instant.parse("2026-11-09T09:00:00Z"));
        publisher.publish(Topics.PROJECTS, w.project().toString(),
                SprintStarted.of(clock.instant(), w.ada(), Fixtures.sprint(w, "Sprint 1", "active")));
        Fixtures.await(WAIT, () -> row(w, "2026-11-09") != null);
        assertThat(row(w, "2026-11-09")).containsEntry("scope", new BigDecimal("8.0"))
                .containsEntry("completed", new BigDecimal("0.0")).containsEntry("count", 2);

        // day 2: A is done
        clock.set(Instant.parse("2026-11-11T15:00:00Z"));
        IssueTransitioned aDone = IssueTransitioned.of(clock.instant(), w.ada(), w.todo(), Fixtures.markDone(jdbc, w, a, 1));
        publisher.publish(Topics.ISSUES, a.id().toString(), aDone);
        Fixtures.await(WAIT, () -> row(w, "2026-11-11") != null);
        assertThat(row(w, "2026-11-11")).containsEntry("completed", new BigDecimal("3.0"))
                .containsEntry("remaining", new BigDecimal("5.0"));
        // day 0 is history: untouched
        assertThat(row(w, "2026-11-09")).containsEntry("completed", new BigDecimal("0.0"));

        // the same event again (a redelivery) after B was also finished: processed_event skips it, so day 2 keeps 3
        Fixtures.markDone(jdbc, w, b, 1);
        publisher.publish(Topics.ISSUES, a.id().toString(), aDone);
        // a later event on the same key (same partition, so after the duplicate) shows the duplicate was handled
        clock.set(Instant.parse("2026-11-12T09:00:00Z"));
        publisher.publish(Topics.ISSUES, a.id().toString(),
                IssueTransitioned.of(clock.instant(), w.ada(), w.todo(), Fixtures.markDone(jdbc, w, a, 2)));
        Fixtures.await(WAIT, () -> row(w, "2026-11-12") != null);
        assertThat(row(w, "2026-11-11")).containsEntry("completed", new BigDecimal("3.0"));
        assertThat(row(w, "2026-11-12")).containsEntry("completed", new BigDecimal("8.0"));
    }

    @Test
    void completedSprintWritesItsReport() {
        World w = Fixtures.world(jdbc);
        UUID done = UUID.randomUUID();
        SprintReport report = new SprintReport(w.sprint(), new BigDecimal("13"), new BigDecimal("8"),
                new BigDecimal("2"), new BigDecimal("1"), List.of(done), List.of(), Instant.parse("2026-11-23T17:00:00Z"));

        publisher.publish(Topics.PROJECTS, w.project().toString(), SprintCompleted.of(clock.instant(), w.ada(),
                Fixtures.sprint(w, "Sprint 1", "closed"), report));

        Fixtures.await(WAIT, () -> jdbc.sql("SELECT count(*) FROM sprint_report WHERE sprint_id = ?").params(w.sprint())
                .query(Integer.class).single() == 1);
        Map<String, Object> row = jdbc.sql("SELECT * FROM sprint_report WHERE sprint_id = ?").params(w.sprint())
                .query().singleRow();
        assertThat(row.get("committed_points")).isEqualTo(new BigDecimal("13.0"));
        assertThat(row.get("completed_points")).isEqualTo(new BigDecimal("8.0"));
    }

    private Map<String, Object> row(World w, String day) {
        return jdbc.sql("""
                        SELECT scope_points AS scope, completed_points AS completed, remaining_points AS remaining,
                               issue_count AS count
                        FROM sprint_day_snapshot WHERE sprint_id = ? AND day = ?""")
                .params(w.sprint(), LocalDate.parse(day))
                .query().listOfRows().stream().findFirst().orElse(null);
    }
}

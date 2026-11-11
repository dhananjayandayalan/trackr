package dev.trakr.workers.reports;

import dev.trakr.contracts.events.CollabEvent;
import dev.trakr.contracts.events.CommentEvent;
import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.IssueCreated;
import dev.trakr.contracts.events.IssueDeleted;
import dev.trakr.contracts.events.IssueDescriptionChanged;
import dev.trakr.contracts.events.IssueLinked;
import dev.trakr.contracts.events.IssueMoved;
import dev.trakr.contracts.events.IssueTransitioned;
import dev.trakr.contracts.events.IssueUpdated;
import dev.trakr.contracts.events.NotificationEvent;
import dev.trakr.contracts.events.ProjectLifecycle;
import dev.trakr.contracts.events.SprintEvent;
import dev.trakr.contracts.events.SprintReport;
import dev.trakr.contracts.events.Topics;
import dev.trakr.contracts.events.TrakrEvent;
import dev.trakr.contracts.events.WorkLogged;
import dev.trakr.contracts.events.WorkflowChanged;
import dev.trakr.workers.kafka.ProcessedEvents;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Burndown and velocity read models (02-data-model §4): today's row of {@code sprint_day_snapshot} for
 * every active sprint an event touches, and {@code sprint_report} when a sprint completes.
 *
 * <p>The row is recomputed from the {@code issue} table, not adjusted by the event's delta. A delta
 * applied twice (a redelivery), skipped (a lost event) or applied out of order would leave the row wrong
 * forever; a recount is right after any event, in any order, however often. processed_event still skips
 * duplicates, so a redelivery costs nothing — but correctness doesn't depend on it.
 */
@Component
class ReportProjector {

    static final String GROUP = "workers-report-projector";

    private final JdbcClient jdbc;
    private final ProcessedEvents processed;
    private final Clock clock;

    ReportProjector(JdbcClient jdbc, ProcessedEvents processed, Clock clock) {
        this.jdbc = jdbc;
        this.processed = processed;
        this.clock = clock;
    }

    @KafkaListener(id = "report-projector", topics = {Topics.ISSUES, Topics.PROJECTS}, groupId = GROUP,
            containerFactory = "recordFactory")
    @Transactional
    public void on(EventEnvelope<TrakrEvent> envelope) {
        processed.runOnce(GROUP, envelope.id(), () -> project(envelope.data()));
    }

    private void project(TrakrEvent event) {
        switch (event) {
            case SprintEvent.SprintStarted e -> recompute(e.snapshot().id());
            case SprintEvent.SprintCompleted e -> report(e.report());
            case SprintEvent e -> { }
            case IssueCreated e -> recomputeAll(Stream.of(e.snapshot().sprintId()));
            case IssueUpdated e -> recomputeAll(Stream.of(e.snapshot().sprintId()));
            case IssueTransitioned e -> recomputeAll(Stream.of(e.snapshot().sprintId()));
            // a move between sprints changes both: the one it left and the one it joined
            case IssueMoved e -> recomputeAll(Stream.of(e.previousSprintId(), e.sprintId()));
            // the event has no snapshot; the deleted row still knows its sprint
            case IssueDeleted e -> recomputeAll(sprintOf(e.issueId()));
            case IssueLinked e -> { }
            case WorkLogged e -> { }
            // the description is in no report
            case IssueDescriptionChanged e -> { }
            case ProjectLifecycle e -> { }
            // a status changed category, or issues moved off a deleted one: the active sprint's numbers changed
            case WorkflowChanged e -> recomputeAll(activeSprintOf(e.projectId()));
            case CommentEvent e -> { }
            case NotificationEvent e -> { }
            case CollabEvent e -> { }
        }
    }

    private void recomputeAll(Stream<UUID> sprintIds) {
        Set<UUID> distinct = new LinkedHashSet<>();
        sprintIds.filter(Objects::nonNull).forEach(distinct::add);
        distinct.forEach(this::recompute);
    }

    /**
     * Today's row for an active sprint, from the issues in it right now: one INSERT … ON CONFLICT DO UPDATE,
     * so the first event of the day creates the row and every later one overwrites it.
     */
    void recompute(UUID sprintId) {
        boolean active = jdbc.sql("SELECT state = 'active' FROM sprint WHERE id = :sprint").param("sprint", sprintId)
                .query(Boolean.class).optional().orElse(false);
        if (!active) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO sprint_day_snapshot (sprint_id, day, scope_points, completed_points, remaining_points,
                                                         issue_count)
                        SELECT :sprint, :day,
                               coalesce(sum(story_points), 0),
                               coalesce(sum(story_points) FILTER (WHERE status_category = 'done'), 0),
                               coalesce(sum(story_points) FILTER (WHERE status_category <> 'done'), 0),
                               count(*)
                        FROM issue
                        WHERE sprint_id = :sprint AND deleted_at IS NULL
                        ON CONFLICT (sprint_id, day) DO UPDATE SET
                          scope_points = excluded.scope_points,
                          completed_points = excluded.completed_points,
                          remaining_points = excluded.remaining_points,
                          issue_count = excluded.issue_count""")
                .param("sprint", sprintId)
                .param("day", LocalDate.now(clock))
                .update();
    }

    private void report(SprintReport report) {
        jdbc.sql("""
                        INSERT INTO sprint_report (sprint_id, committed_points, completed_points, added_points, removed_points,
                                                   completed_issue_ids, incomplete_issue_ids, closed_at)
                        VALUES (:sprint, :committed, :completed, :added, :removed, :completedIds, :incompleteIds, :closedAt)
                        ON CONFLICT (sprint_id) DO NOTHING""")
                .param("sprint", report.sprintId())
                .param("committed", report.committedPoints())
                .param("completed", report.completedPoints())
                .param("added", report.addedPoints())
                .param("removed", report.removedPoints())
                .param("completedIds", report.completedIssueIds().toArray(UUID[]::new))
                .param("incompleteIds", report.incompleteIssueIds().toArray(UUID[]::new))
                .param("closedAt", Timestamp.from(report.closedAt()))
                .update();
    }

    private Stream<UUID> activeSprintOf(UUID projectId) {
        return jdbc.sql("SELECT id FROM sprint WHERE project_id = :project AND state = 'active'")
                .param("project", projectId).query(UUID.class).list().stream();
    }

    // a list, not optional(): the column may be NULL (a backlog issue), and an Optional can't hold null
    private Stream<UUID> sprintOf(UUID issueId) {
        return jdbc.sql("SELECT sprint_id FROM issue WHERE id = :id").param("id", issueId)
                .query(UUID.class).list().stream();
    }
}

package dev.trakr.api.sprint;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.IssueDetails;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.MoveIssue;
import dev.trakr.api.issue.PatchIssue.Value;
import dev.trakr.api.issue.TransitionIssue;
import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.Template;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.sprint.BacklogView.BacklogRow;
import dev.trakr.api.sprint.SprintCommands.CompleteSprint;
import dev.trakr.api.sprint.SprintCommands.StartSprint;
import dev.trakr.contracts.events.IssueMoved;
import dev.trakr.contracts.events.SprintEvent.SprintCompleted;
import dev.trakr.contracts.events.SprintReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.AssertablePublishedEvents;

/** The sprint module with everything it depends on (issue, board, workflow, project, …). */
@ApplicationModuleTest(BootstrapMode.ALL_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class SprintServiceIT {

    private static final LocalDate MONDAY = LocalDate.parse("2026-11-09");

    private final SprintService sprints;
    private final IssueService issues;
    private final ProjectService projects;
    private final IdentityService identities;

    @Autowired
    SprintServiceIT(SprintService sprints, IssueService issues, ProjectService projects, IdentityService identities) {
        this.sprints = sprints;
        this.issues = issues;
        this.projects = projects;
        this.identities = identities;
    }

    @Test
    void secondActiveSprintIs409() {
        UserView owner = scrumProject("TWO");
        SprintView first = sprints.create(owner.id(), "TWO", null);
        SprintView second = sprints.create(owner.id(), "TWO", null);
        sprints.start(owner.id(), first.id(), start("One"));

        assertThat(second.name()).isEqualTo("TWO Sprint 2");
        assertThatThrownBy(() -> sprints.start(owner.id(), second.id(), start("Two")))
                .isInstanceOfSatisfying(TrakrException.class,
                        e -> assertThat(e.code()).isEqualTo("SPRINT_ALREADY_ACTIVE"));
    }

    @Test
    void completeMovesOpenIssuesToTheTopOfTheBacklogInTheirOrder(AssertablePublishedEvents events) {
        UserView owner = scrumProject("CMP");
        SprintView sprint = sprints.create(owner.id(), "CMP", "Sprint A");
        issue(owner, "CMP", "Already in the backlog", null, "1");
        IssueDetails a = issue(owner, "CMP", "A", sprint.id(), "3");
        IssueDetails b = issue(owner, "CMP", "B", sprint.id(), "5");
        IssueDetails c = issue(owner, "CMP", "C", sprint.id(), "2");
        sprints.start(owner.id(), sprint.id(), start("Sprint A"));
        // after the start: one issue added (8 points), B finished, C removed back to the backlog
        IssueDetails added = issue(owner, "CMP", "Added later", sprint.id(), "8");
        issues.transition(owner.id(), b.key(), new TransitionIssue(statusNamed(owner, b.key(), "Done"), b.version()));
        issues.move(owner.id(), c.key(), new MoveIssue(null, new Value<>(null), null, null, c.version()));

        SprintReport report = sprints.complete(owner.id(), sprint.id(), new CompleteSprint(null));

        // committed A+B+C = 10; completed B = 5; added 8; removed C = 2
        assertThat(report.committedPoints()).isEqualByComparingTo("10");
        assertThat(report.completedPoints()).isEqualByComparingTo("5");
        assertThat(report.addedPoints()).isEqualByComparingTo("8");
        assertThat(report.removedPoints()).isEqualByComparingTo("2");
        assertThat(report.completedIssueIds()).containsExactly(b.id());
        assertThat(report.incompleteIssueIds()).containsExactly(a.id(), added.id());

        // the open ones are now first in the backlog, A before "Added later", as they were in the sprint
        List<String> backlog = sprints.backlog(owner.id(), "CMP", BacklogFilter.none()).backlog().issues().stream()
                .map(BacklogRow::summary).toList();
        assertThat(backlog).containsExactly("A", "Added later", "Already in the backlog", "C");
        assertThat(sprints.get(owner.id(), sprint.id()).state()).isEqualTo(SprintState.CLOSED);
        assertThat(events.ofType(SprintCompleted.class)).singleElement()
                .satisfies(event -> assertThat(event.report()).isEqualTo(report));
        assertThat(events.ofType(IssueMoved.class).matching(event -> event.previousSprintId() != null)).hasSize(3);
    }

    @Test
    void completeCanMoveOpenIssuesToTheNextSprint() {
        UserView owner = scrumProject("NXT");
        SprintView current = sprints.create(owner.id(), "NXT", null);
        SprintView next = sprints.create(owner.id(), "NXT", null);
        issue(owner, "NXT", "Carried over", current.id(), "3");
        sprints.start(owner.id(), current.id(), start("Now"));

        sprints.complete(owner.id(), current.id(), new CompleteSprint(next.id()));

        BacklogView backlog = sprints.backlog(owner.id(), "NXT", BacklogFilter.none());
        assertThat(backlog.activeSprint()).isNull();
        assertThat(backlog.plannedSprints()).singleElement().satisfies(list -> {
            assertThat(list.sprint().id()).isEqualTo(next.id());
            assertThat(list.issues()).extracting(BacklogRow::summary).containsExactly("Carried over");
            assertThat(list.points().todo()).isEqualByComparingTo("3");
        });
    }

    @Test
    void deletingAPlannedSprintReturnsItsIssuesToTheBacklog() {
        UserView owner = scrumProject("DEL");
        SprintView sprint = sprints.create(owner.id(), "DEL", null);
        issue(owner, "DEL", "Was planned", sprint.id(), null);

        sprints.delete(owner.id(), sprint.id());

        BacklogView backlog = sprints.backlog(owner.id(), "DEL", BacklogFilter.none());
        assertThat(backlog.plannedSprints()).isEmpty();
        assertThat(backlog.backlog().issues()).extracting(BacklogRow::summary).containsExactly("Was planned");
    }

    @Test
    void kanbanProjectsHaveNoSprints() {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        projects.create(owner.id(), new NewProject("Kanban", "KAN", Template.KANBAN, null, Access.PRIVATE, null, "cyan",
                List.of()));

        assertThatThrownBy(() -> sprints.create(owner.id(), "KAN", null))
                .isInstanceOfSatisfying(TrakrException.class, e -> assertThat(e.code()).isEqualTo("SPRINTS_NOT_ENABLED"));
    }

    private UserView scrumProject(String key) {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        projects.create(owner.id(), new NewProject("Project " + key, key, Template.SCRUM, null, Access.PRIVATE, null,
                "cyan", List.of()));
        return owner;
    }

    private IssueDetails issue(UserView owner, String key, String summary, UUID sprintId, String points) {
        return issues.create(owner.id(), new CreateIssue(key, IssueType.STORY, summary, null, null, null, sprintId,
                null, points == null ? null : new BigDecimal(points), null, List.of()));
    }

    // from the project's details, not the issue's transitions: those are workflow types, and sprint may not use workflow
    private UUID statusNamed(UserView owner, String issueKey, String name) {
        return projects.open(owner.id(), issueKey.substring(0, issueKey.indexOf('-'))).statuses().stream()
                .filter(s -> s.name().equals(name)).findFirst().orElseThrow().id();
    }

    private static StartSprint start(String name) {
        return new StartSprint(name, null, MONDAY, MONDAY.plusDays(14));
    }
}

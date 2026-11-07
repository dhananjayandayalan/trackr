package dev.trakr.api.issue;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static dev.trakr.jooq.Tables.WORKFLOW_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.Template;
import dev.trakr.api.workflow.TransitionNotAllowed;
import dev.trakr.api.workflow.WorkflowStatusView;
import dev.trakr.contracts.events.IssueCreated;
import dev.trakr.contracts.events.IssueTransitioned;
import dev.trakr.contracts.events.IssueUpdated;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.modulith.test.Scenario;

/**
 * The issue module with what it depends on (project, workflow, identity, workspace, shared) and
 * nothing else: no board module. ALL_DEPENDENCIES, not DIRECT: ProjectService needs workspace,
 * which issue itself doesn't use.
 *
 * <p>Sequence numbers, decided here: an event's sequence is the issue's version after the change.
 * A new row is version 0, so IssueCreated has sequence 0 and the first edit has 1.
 */
@ApplicationModuleTest(BootstrapMode.ALL_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class IssueServiceIT {

    private final IssueService issues;
    private final ProjectService projects;
    private final IdentityService identities;
    private final DSLContext dsl;

    @Autowired
    IssueServiceIT(IssueService issues, ProjectService projects, IdentityService identities, DSLContext dsl) {
        this.issues = issues;
        this.projects = projects;
        this.identities = identities;
        this.dsl = dsl;
    }

    @Test
    void createPublishesIssueCreatedWithSequenceZero(Scenario scenario) {
        UserView owner = ownerOfProject("EVT");

        scenario.stimulate(() -> issues.create(owner.id(), story("EVT", "First")))
                .andWaitForEventOfType(IssueCreated.class)
                .toArriveAndVerify(event -> {
                    assertThat(event.sequence()).isZero();
                    assertThat(event.snapshot().key()).isEqualTo("EVT-1");
                    assertThat(event.snapshot().statusCategory()).isEqualTo("todo");
                    assertThat(event.actorId()).isEqualTo(owner.id());
                    assertThat(event.type()).isEqualTo("trakr.issue.created.v1");
                });
    }

    @Test
    void patchPublishesTheChangesWithTheNewVersion(AssertablePublishedEvents events) {
        UserView owner = ownerOfProject("PAT");
        issues.create(owner.id(), story("PAT", "Before"));

        IssueDetails patched = issues.patch(owner.id(), "PAT-1", 0, PatchIssue.none().withSummary("After"));

        assertThat(patched.version()).isEqualTo(1);
        assertThat(events.ofType(IssueUpdated.class)).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(1);
            assertThat(event.changes()).containsOnlyKeys("summary");
            assertThat(event.changes().get("summary")).isEqualTo(new IssueUpdated.Change("Before", "After"));
        });
    }

    @Test
    void patchThatChangesNothingPublishesNothing(AssertablePublishedEvents events) {
        UserView owner = ownerOfProject("NOP");
        issues.create(owner.id(), story("NOP", "Same"));

        IssueDetails unchanged = issues.patch(owner.id(), "NOP-1", 0, PatchIssue.none().withSummary("Same"));

        assertThat(unchanged.version()).isZero();
        assertThat(events.ofType(IssueUpdated.class)).isEmpty();
    }

    @Test
    void transitionNotInTheWorkflowIsRefused() {
        UserView owner = ownerOfProject("WFL");
        IssueDetails issue = issues.create(owner.id(), story("WFL", "Strict"));
        WorkflowStatusView done = statusNamed(owner, "WFL-1", "Done");
        // make the workflow stricter: no To do → Done (the template allows every move)
        dsl.deleteFrom(WORKFLOW_TRANSITION)
                .where(WORKFLOW_TRANSITION.FROM_STATUS_ID.eq(issue.status().id()),
                        WORKFLOW_TRANSITION.TO_STATUS_ID.eq(done.id()))
                .execute();

        assertThatThrownBy(() -> issues.transition(owner.id(), "WFL-1", new TransitionIssue(done.id(), 0)))
                .isInstanceOfSatisfying(TransitionNotAllowed.class,
                        e -> assertThat(e.code()).isEqualTo("TRANSITION_NOT_ALLOWED"));
        assertThat(issues.transitions(owner.id(), "WFL-1")).extracting(WorkflowStatusView::name)
                .doesNotContain("Done");
    }

    @Test
    void transitionToDoneResolvesAndPublishes(AssertablePublishedEvents events) {
        UserView owner = ownerOfProject("TRN");
        IssueDetails issue = issues.create(owner.id(), story("TRN", "Finish me"));
        WorkflowStatusView done = statusNamed(owner, "TRN-1", "Done");

        IssueDetails resolved = issues.transition(owner.id(), "TRN-1", new TransitionIssue(done.id(), 0));

        assertThat(resolved.status().category()).isEqualTo("done");
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(events.ofType(IssueTransitioned.class)).singleElement().satisfies(event -> {
            assertThat(event.fromStatusId()).isEqualTo(issue.status().id());
            assertThat(event.toStatusId()).isEqualTo(done.id());
            assertThat(event.toCategory()).isEqualTo("done");
            assertThat(event.sequence()).isEqualTo(1);
        });
    }

    private UserView ownerOfProject(String key) {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        projects.create(owner.id(), new NewProject("Project " + key, key, Template.SCRUM, null, Access.PRIVATE, null,
                "cyan", List.of()));
        return owner;
    }

    private WorkflowStatusView statusNamed(UserView owner, String issueKey, String name) {
        return issues.transitions(owner.id(), issueKey).stream()
                .filter(status -> status.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static CreateIssue story(String projectKey, String summary) {
        return new CreateIssue(projectKey, IssueType.STORY, summary, null, null, null, null, null, null, null,
                List.of());
    }
}

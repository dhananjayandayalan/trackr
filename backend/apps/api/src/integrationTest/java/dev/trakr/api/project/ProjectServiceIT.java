package dev.trakr.api.project;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.shared.web.TrakrException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.Scenario;

/**
 * The project module on its own: Modulith starts only project and the modules it depends on
 * (workspace, identity, shared). Workflow and board are not in the context — the test proves that
 * project works without them, and checks the event they would react to.
 */
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class ProjectServiceIT {

    private final ProjectService projects;
    private final IdentityService identities;

    // constructor injection: Modulith's module test flags field injection of another module's beans
    @Autowired
    ProjectServiceIT(ProjectService projects, IdentityService identities) {
        this.projects = projects;
        this.identities = identities;
    }

    @Test
    void createPublishesProjectCreated(Scenario scenario) {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");

        scenario.stimulate(() -> projects.create(owner.id(), newProject("SCR", Template.SCRUM)))
                .andWaitForEventOfType(ProjectCreated.class)
                .toArriveAndVerify(event -> {
                    assertThat(event.key()).isEqualTo("SCR");
                    assertThat(event.template()).isEqualTo(Template.SCRUM);
                    assertThat(event.actorId()).isEqualTo(owner.id());
                });
    }

    @Test
    void workflowAndBoardAreNotPartOfThisTest(ApplicationContext context) {
        assertThat(context.containsBean("templateSeeder")).isFalse();
        assertThat(context.containsBean("boardSeeder")).isFalse();
        assertThat(context.containsBean("projectService")).isTrue();
    }

    @Test
    void creatorBecomesTheOnlyAdmin() {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");

        projects.create(owner.id(), newProject("ADM", Template.BLANK));

        assertThat(projects.members(owner.id(), "ADM"))
                .singleElement()
                .satisfies(member -> assertThat(member.role()).isEqualTo(ProjectRole.ADMIN));
        assertThatThrownBy(() -> projects.removeMember(owner.id(), "ADM", owner.id()))
                .isInstanceOfSatisfying(TrakrException.class, e -> assertThat(e.code()).isEqualTo("LAST_ADMIN"));
    }

    @Test
    void duplicateKeyInTheSameWorkspaceIsProjectKeyTaken() {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        projects.create(owner.id(), newProject("DUP", Template.BLANK));

        assertThatThrownBy(() -> projects.create(owner.id(), newProject("DUP", Template.KANBAN)))
                .isInstanceOfSatisfying(TrakrException.class,
                        e -> assertThat(e.code()).isEqualTo("PROJECT_KEY_TAKEN"));
    }

    private static NewProject newProject(String key, Template template) {
        return new NewProject("Project " + key, key, template, null, Access.PRIVATE, null, "cyan", List.of());
    }
}

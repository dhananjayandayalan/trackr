package dev.trakr.flows;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.api.ApiApplication;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.board.BoardFilter;
import dev.trakr.api.board.BoardService;
import dev.trakr.api.board.BoardView;
import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.Template;
import dev.trakr.api.sprint.SprintCommands.StartSprint;
import dev.trakr.api.sprint.SprintService;
import dev.trakr.api.sprint.SprintView;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Performance smoke: a 500-card sprint board, served from the service (no HTTP), median under 50 ms.
 * Off by default — timings depend on the machine; run with
 * {@code ./gradlew :apps:api:integrationTest --tests '*BoardPerfIT' -Pperf=true}.
 *
 * <p>Outside dev.trakr.api on purpose: it drives issue, sprint and board together. Inside a module's
 * package Modulith would count those calls as that module's dependencies, and in the root package as the
 * root's — both make a cycle with the modules' own tests. Out here Modulith doesn't look, and
 * {@code classes = ApiApplication.class} tells Spring which app to start (it can't search up from here).
 */
@Tag("perf")
@EnabledIfSystemProperty(named = "perf", matches = "true")
@SpringBootTest(classes = ApiApplication.class, properties = "spring.datasource.hikari.connection-timeout=30000")
@Import(TestcontainersConfiguration.class)
class BoardPerfIT {

    private static final Logger log = LoggerFactory.getLogger(BoardPerfIT.class);
    private static final int CARDS = 500;

    private final BoardService boards;
    private final IssueService issues;
    private final SprintService sprints;
    private final ProjectService projects;
    private final IdentityService identities;

    @Autowired
    BoardPerfIT(BoardService boards, IssueService issues, SprintService sprints, ProjectService projects,
            IdentityService identities) {
        this.boards = boards;
        this.issues = issues;
        this.sprints = sprints;
        this.projects = projects;
        this.identities = identities;
    }

    @Test
    void fiveHundredCardBoardIsServedUnder50Milliseconds() {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        projects.create(owner.id(), new NewProject("Perf", "PERF", Template.SCRUM, null, Access.PRIVATE, null, "cyan",
                List.of()));
        SprintView sprint = sprints.create(owner.id(), "PERF", null);
        for (int i = 0; i < CARDS; i++) {
            issues.create(owner.id(), new CreateIssue("PERF", IssueType.TASK, "Card " + i, null, owner.id(), null,
                    sprint.id(), null, null, null, List.of()));
        }
        sprints.start(owner.id(), sprint.id(), new StartSprint("Perf", null, LocalDate.parse("2026-11-09"),
                LocalDate.parse("2026-11-23")));

        // warm-up: JIT, connection pool, Postgres caches
        for (int i = 0; i < 20; i++) {
            boards.view(owner.id(), "PERF", BoardFilter.none());
        }
        long[] millis = new long[50];
        for (int i = 0; i < millis.length; i++) {
            long start = System.nanoTime();
            BoardView view = boards.view(owner.id(), "PERF", BoardFilter.none());
            millis[i] = (System.nanoTime() - start) / 1_000_000;
            assertThat(view.columns().getFirst().cards()).hasSize(CARDS);
        }
        Arrays.sort(millis);
        log.info("board view with {} cards: median={} ms p95={} ms max={} ms", CARDS, millis[millis.length / 2],
                millis[(int) (millis.length * 0.95)], millis[millis.length - 1]);

        assertThat(millis[millis.length / 2]).isLessThan(50);
    }
}

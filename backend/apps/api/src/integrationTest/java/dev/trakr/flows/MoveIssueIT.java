package dev.trakr.flows;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static dev.trakr.jooq.Tables.WORKFLOW_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.api.ApiApplication;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.board.BoardFilter;
import dev.trakr.api.board.BoardService;
import dev.trakr.api.board.BoardView;
import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.IssueDetails;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.MoveIssue;
import dev.trakr.api.issue.MoveResult;
import dev.trakr.api.issue.PatchIssue.Value;
import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.Template;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.sprint.SprintCommands.StartSprint;
import dev.trakr.api.sprint.SprintService;
import dev.trakr.api.sprint.SprintView;
import dev.trakr.api.workflow.TransitionNotAllowed;
import dev.trakr.contracts.events.IssueMoved;
import dev.trakr.contracts.events.IssueTransitioned;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * POST /issues/{key}/move at the service level. Same properties as IssueNumberConcurrencyIT, so both
 * share one Spring context (and the longer connection timeout the concurrent test needs).
 *
 * <p>Outside dev.trakr.api on purpose: it drives issue, sprint and board together. Inside a module's
 * package Modulith would count those calls as that module's dependencies, and in the root package as the
 * root's — both make a cycle with the modules' own tests. Out here Modulith doesn't look, and
 * {@code classes = ApiApplication.class} tells Spring which app to start (it can't search up from here).
 */
@SpringBootTest(classes = ApiApplication.class, properties = "spring.datasource.hikari.connection-timeout=30000")
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class MoveIssueIT {

    private static final LocalDate MONDAY = LocalDate.parse("2026-11-09");

    private final IssueService issues;
    private final SprintService sprints;
    private final BoardService boards;
    private final ProjectService projects;
    private final IdentityService identities;
    private final DSLContext dsl;

    @Autowired
    MoveIssueIT(IssueService issues, SprintService sprints, BoardService boards, ProjectService projects,
            IdentityService identities, DSLContext dsl) {
        this.issues = issues;
        this.sprints = sprints;
        this.boards = boards;
        this.projects = projects;
        this.identities = identities;
        this.dsl = dsl;
    }

    record Fixture(UserView owner, String key, SprintView sprint) {
    }

    @Test
    void movesWithinAColumn() {
        Fixture f = scrumWithActiveSprint("RNK");
        create(f, "A", f.sprint().id());
        create(f, "B", f.sprint().id());
        create(f, "C", f.sprint().id());

        MoveResult result = issues.move(f.owner().id(), "RNK-3", new MoveIssue(null, null, "RNK-1", "RNK-2", 0));

        assertThat(result.warnings()).isEmpty();
        assertThat(result.issue().version()).isEqualTo(1);
        assertThat(cardsOf(f, "To do")).containsExactly("RNK-1", "RNK-3", "RNK-2");
    }

    @Test
    void acrossColumnsTransitionsAndPublishesBothEvents(ApplicationEvents events) {
        Fixture f = scrumWithActiveSprint("COL");
        create(f, "A", f.sprint().id());
        UUID inProgress = statusNamed(f, "COL-1", "In progress");

        MoveResult result = issues.move(f.owner().id(), "COL-1", new MoveIssue(inProgress, null, null, null, 0));

        assertThat(result.issue().status().name()).isEqualTo("In progress");
        assertThat(cardsOf(f, "In progress")).containsExactly("COL-1");
        assertThat(events.stream(IssueMoved.class)).singleElement()
                .satisfies(event -> assertThat(event.sequence()).isEqualTo(1));
        assertThat(events.stream(IssueTransitioned.class)).singleElement()
                .satisfies(event -> assertThat(event.toStatusId()).isEqualTo(inProgress));
    }

    @Test
    void acrossColumnsTheWorkflowRefuses() {
        Fixture f = scrumWithActiveSprint("REF");
        IssueDetails issue = create(f, "A", f.sprint().id());
        UUID done = statusNamed(f, "REF-1", "Done");
        dsl.deleteFrom(WORKFLOW_TRANSITION)
                .where(WORKFLOW_TRANSITION.FROM_STATUS_ID.eq(issue.status().id()), WORKFLOW_TRANSITION.TO_STATUS_ID.eq(done))
                .execute();

        assertThatThrownBy(() -> issues.move(f.owner().id(), "REF-1", new MoveIssue(done, null, null, null, 0)))
                .isInstanceOf(TransitionNotAllowed.class);
        assertThat(issues.get(f.owner().id(), "REF-1").version()).isZero();
    }

    @Test
    void intoASprintAndBackToTheBacklog() {
        Fixture f = scrumWithActiveSprint("SPR");
        create(f, "In the sprint", f.sprint().id());
        create(f, "In the backlog", null);

        MoveResult into = issues.move(f.owner().id(), "SPR-2", new MoveIssue(null, new Value<>(f.sprint().id()), null,
                null, 0));
        assertThat(into.issue().sprint()).isNotNull();
        // no neighbours given: the end of the sprint's list
        assertThat(cardsOf(f, "To do")).containsExactly("SPR-1", "SPR-2");

        MoveResult back = issues.move(f.owner().id(), "SPR-2", new MoveIssue(null, new Value<>(null), null, null, 1));
        assertThat(back.issue().sprint()).isNull();
        assertThat(cardsOf(f, "To do")).containsExactly("SPR-1");
    }

    @Test
    void staleVersionIs412() {
        Fixture f = scrumWithActiveSprint("STL");
        create(f, "A", f.sprint().id());
        create(f, "B", f.sprint().id());
        issues.move(f.owner().id(), "STL-2", new MoveIssue(null, null, null, "STL-1", 0));

        assertThatThrownBy(() -> issues.move(f.owner().id(), "STL-2", new MoveIssue(null, null, "STL-1", null, 0)))
                .isInstanceOfSatisfying(TrakrException.class, e -> assertThat(e.code()).isEqualTo("VERSION_MISMATCH"));
    }

    @Test
    void neighboursFromAnotherListGiveAWarningAndTheEndOfTheList() {
        Fixture f = scrumWithActiveSprint("NBR");
        create(f, "A", f.sprint().id());
        create(f, "B", f.sprint().id());
        create(f, "In the backlog", null);

        // NBR-3 is not in the sprint: maybe someone moved it meanwhile
        MoveResult result = issues.move(f.owner().id(), "NBR-1", new MoveIssue(null, null, "NBR-3", "NBR-2", 0));

        assertThat(result.warnings()).containsExactly("NEIGHBOURS_CHANGED");
        assertThat(cardsOf(f, "To do")).containsExactly("NBR-2", "NBR-1");
    }

    @Test
    void movingIntoAFullColumnWarnsButMoves() {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        projects.create(owner.id(), new NewProject("Kanban", "WIP", Template.KANBAN, null, Access.PRIVATE, null, "cyan",
                List.of()));
        UUID inProgress = null;
        for (int i = 1; i <= 5; i++) {
            create(owner, "WIP", "Task " + i, null);
            if (inProgress == null) {
                inProgress = issues.transitions(owner.id(), "WIP-1").stream()
                        .filter(s -> s.name().equals("In progress")).findFirst().orElseThrow().id();
            }
        }
        // the kanban template's In progress column has a WIP limit of 4
        for (int i = 1; i <= 4; i++) {
            assertThat(issues.move(owner.id(), "WIP-" + i, new MoveIssue(inProgress, null, null, null, 0)).warnings())
                    .isEmpty();
        }

        MoveResult fifth = issues.move(owner.id(), "WIP-5", new MoveIssue(inProgress, null, null, null, 0));

        assertThat(fifth.warnings()).containsExactly("WIP_LIMIT_EXCEEDED");
        assertThat(fifth.issue().status().name()).isEqualTo("In progress");
    }

    /**
     * 20 cards dropped into the same gap at the same moment. Each reads the neighbours' ranks; without a
     * shared lock, all 20 would compute the same midpoint. With the project's rank lock they take turns,
     * and each lands between the lower neighbour and the card the previous one just placed.
     */
    @Test
    void twentyConcurrentMovesIntoTheSameGapAllGetUniqueRanks() throws Exception {
        Fixture f = scrumWithActiveSprint("GAP");
        create(f, "Top", f.sprint().id());
        create(f, "Bottom", f.sprint().id());
        for (int i = 0; i < 20; i++) {
            create(f, "Mover " + i, null);
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MoveResult>> results = new ArrayList<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 3; i <= 22; i++) {
                String key = "GAP-" + i;
                results.add(threads.submit(() -> {
                    start.await();
                    return issues.move(f.owner().id(), key,
                            new MoveIssue(null, new Value<>(f.sprint().id()), "GAP-1", "GAP-2", 0));
                }));
            }
            start.countDown();
        }

        List<String> ranks = new ArrayList<>();
        for (Future<MoveResult> result : results) {
            MoveResult moved = result.get();
            assertThat(moved.warnings()).isEmpty();
            ranks.add(moved.issue().rank());
        }
        String top = issues.get(f.owner().id(), "GAP-1").rank();
        String bottom = issues.get(f.owner().id(), "GAP-2").rank();
        assertThat(ranks).doesNotHaveDuplicates().allSatisfy(rank -> assertThat(rank).isGreaterThan(top).isLessThan(bottom));
        List<String> column = cardsOf(f, "To do");
        assertThat(column).hasSize(22).startsWith("GAP-1").endsWith("GAP-2");
    }

    private Fixture scrumWithActiveSprint(String key) {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        projects.create(owner.id(), new NewProject("Project " + key, key, Template.SCRUM, null, Access.PRIVATE, null,
                "cyan", List.of()));
        SprintView sprint = sprints.create(owner.id(), key, null);
        sprints.start(owner.id(), sprint.id(), new StartSprint("Now", null, MONDAY, MONDAY.plusDays(14)));
        return new Fixture(owner, key, sprint);
    }

    private IssueDetails create(Fixture f, String summary, UUID sprintId) {
        return create(f.owner(), f.key(), summary, sprintId);
    }

    private IssueDetails create(UserView owner, String key, String summary, UUID sprintId) {
        return issues.create(owner.id(), new CreateIssue(key, IssueType.TASK, summary, null, null, null, sprintId, null,
                null, null, List.of()));
    }

    private UUID statusNamed(Fixture f, String issueKey, String name) {
        return issues.transitions(f.owner().id(), issueKey).stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow().id();
    }

    private List<String> cardsOf(Fixture f, String column) {
        BoardView board = boards.view(f.owner().id(), f.key(), BoardFilter.none());
        return board.columns().stream().filter(c -> c.name().equals(column)).findFirst().orElseThrow()
                .cards().stream().map(BoardView.Card::key).toList();
    }
}

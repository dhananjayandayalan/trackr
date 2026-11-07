package dev.trakr.api.issue;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.Template;
import dev.trakr.kernel.Rank;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 50 creates in one project at the same moment, each in its own transaction on its own virtual
 * thread. The UPDATE … RETURNING in IssueNumberAllocator makes them queue on the project row, so
 * the numbers come out 1..50 with no gaps and no duplicates — and because each create reads the
 * last rank only after taking that lock, the ranks are distinct and in creation order too.
 *
 * <p>The connection timeout is raised for this test only: up to 50 transactions wait for 20
 * pooled connections while the creates run one after another, longer than the dev 3 s allows on a
 * slow machine.
 */
@SpringBootTest(properties = "spring.datasource.hikari.connection-timeout=30000")
@Import(TestcontainersConfiguration.class)
class IssueNumberConcurrencyIT {

    private static final int CREATES = 50;

    private final IssueService issues;
    private final ProjectService projects;
    private final IdentityService identities;

    // constructor injection: Modulith's module tests verify every test class in a module's package
    @Autowired
    IssueNumberConcurrencyIT(IssueService issues, ProjectService projects, IdentityService identities) {
        this.issues = issues;
        this.projects = projects;
        this.identities = identities;
    }

    @Test
    void concurrentCreatesGetNumbersOneToFiftyWithoutGaps() throws Exception {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        projects.create(owner.id(), new NewProject("Race", "RACE", Template.SCRUM, null, Access.PRIVATE, null, "cyan",
                List.of()));
        CountDownLatch start = new CountDownLatch(1);

        List<Future<IssueDetails>> results = new ArrayList<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CREATES; i++) {
                String summary = "Issue " + i;
                results.add(threads.submit(() -> {
                    start.await();
                    return issues.create(owner.id(), new CreateIssue("RACE", IssueType.TASK, summary, null, null,
                            null, null, null, null, null, List.of()));
                }));
            }
            // all 50 are waiting on the latch: release them together
            start.countDown();
        }

        List<IssueDetails> created = new ArrayList<>();
        for (Future<IssueDetails> result : results) {
            created.add(result.get());
        }
        List<Long> numbers = created.stream()
                .map(issue -> Long.parseLong(issue.key().substring("RACE-".length())))
                .sorted()
                .toList();
        assertThat(numbers).containsExactlyElementsOf(LongStream.rangeClosed(1, CREATES).boxed().toList());

        // in number order, the ranks climb: each create appended after the one committed before it
        List<String> ranksByNumber = created.stream()
                .sorted(Comparator.comparingLong(issue -> Long.parseLong(issue.key().substring("RACE-".length()))))
                .map(IssueDetails::rank)
                .toList();
        assertThat(ranksByNumber).isSorted().doesNotHaveDuplicates();
        assertThat(ranksByNumber.getFirst()).isEqualTo(Rank.between(null, null));
    }
}

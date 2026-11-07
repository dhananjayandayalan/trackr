package dev.trakr.api.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The board read model against the dev seed (db/dev/R__dev_seed.sql), which the integration tests load
 * because dev is the default profile. The seed's ids are fixed, so the whole MAR board can be compared
 * with a checked-in snapshot. Nothing in the test suite writes to the seed's projects.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BoardQueryIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GRACE = TestBrowser.seedId("user:grace").toString();

    private final MockMvcTester mvc;

    // constructor injection: Modulith's module tests verify every test class in a module's package
    @Autowired
    BoardQueryIT(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    /**
     * To accept a deliberate change of the board's shape or of the seed:
     * {@code ./gradlew :apps:api:integrationTest --tests '*BoardQueryIT' -PupdateSnapshots=true}, then
     * review the diff of board-MAR.json like any other change.
     */
    @Test
    void marBoardMatchesTheSnapshot() throws IOException {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        MvcTestResult board = ada.get("/api/v1/projects/MAR/board");
        assertThat(board).hasStatusOk();

        JsonNode actual = JSON.readTree(contentOf(board));
        Path snapshot = Path.of(System.getProperty("snapshots.dir", "src/integrationTest/resources/snapshots"))
                .resolve("board-MAR.json");
        boolean missing = !Files.exists(snapshot);
        if (Boolean.getBoolean("snapshots.update") || missing) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n",
                    StandardCharsets.UTF_8);
        }
        // a missing snapshot is written, but the test still fails: a new snapshot must be reviewed, not trusted
        assertThat(missing).as("%s did not exist; it was written now — review it and run again", snapshot).isFalse();

        assertThat(actual).isEqualTo(JSON.readTree(Files.readString(snapshot, StandardCharsets.UTF_8)));
    }

    @Test
    void scrumBoardShowsTheActiveSprintInColumns() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        assertThat(ada.get("/api/v1/projects/MAR/board")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.board.type", v -> v.assertThat().isEqualTo("scrum"))
                .hasPathSatisfying("$.sprint.name", v -> v.assertThat().isEqualTo("MAR Sprint 2"))
                .hasPathSatisfying("$.columns[*].name",
                        v -> v.assertThat().asArray().containsExactly("To do", "In progress", "In review", "Done"))
                // 30 issues in sprint 2, subtasks included, epics not
                .hasPathSatisfying("$.columns[*].count", v -> v.assertThat().asArray().containsExactly(8, 8, 7, 7))
                .hasPathSatisfying("$.columns[0].cards[0].key", v -> v.assertThat().isEqualTo("MAR-24"))
                .hasPathSatisfying("$.epics.length()", v -> v.assertThat().isEqualTo(3))
                .hasPathSatisfying("$.people.length()", v -> v.assertThat().isEqualTo(2));
    }

    @Test
    void filtersNarrowTheCardsButNotTheCounts() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        List<String> assignees = JsonPath.read(contentOf(ada.get("/api/v1/projects/MAR/board?assignee=" + GRACE)),
                "$.columns[*].cards[*].assigneeId");
        assertThat(assignees).isNotEmpty().containsOnly(GRACE);

        String bugs = contentOf(ada.get("/api/v1/projects/MAR/board?type=bug"));
        assertThat(JsonPath.<List<String>>read(bugs, "$.columns[*].cards[*].type")).isNotEmpty().containsOnly("bug");
        assertThat(JsonPath.<List<Integer>>read(bugs, "$.columns[*].count")).containsExactly(8, 8, 7, 7);

        List<String> both = JsonPath.read(contentOf(ada.get(
                "/api/v1/projects/MAR/board?type=story&assignee=" + GRACE)), "$.columns[*].cards[*].key");
        assertThat(both).isNotEmpty().allSatisfy(key -> assertThat(key).startsWith("MAR-"));

        List<String> pricing = JsonPath.read(contentOf(ada.get("/api/v1/projects/MAR/board?q=PRICING")),
                "$.columns[*].cards[*].summary");
        assertThat(pricing).isNotEmpty().allSatisfy(summary -> assertThat(summary).containsIgnoringCase("pricing"));

        // the seed's sprint-2 issues were last updated days ago
        assertThat(JsonPath.<List<String>>read(contentOf(ada.get("/api/v1/projects/MAR/board?recent=true")),
                "$.columns[*].cards[*].key")).isEmpty();

        assertThat(ada.get("/api/v1/projects/MAR/board?type=epic")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void kanbanBoardIgnoresSprintsAndShowsTwoWeeksOfDone() {
        TestBrowser grace = TestBrowser.loggedIn(mvc, "grace@trakr.dev");

        assertThat(grace.get("/api/v1/projects/OPS/board")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.sprint", v -> v.assertThat().isNull())
                .hasPathSatisfying("$.columns[*].name",
                        v -> v.assertThat().asArray().containsExactly("Selected", "In progress", "Done"))
                // Backlog is not a column; Done keeps only the 14 resolved in the last 14 days
                .hasPathSatisfying("$.columns[*].count", v -> v.assertThat().asArray().containsExactly(20, 6, 14))
                .hasPathSatisfying("$.columns[1].wipLimit", v -> v.assertThat().isEqualTo(4))
                .hasPathSatisfying("$.columns[1].overLimit", v -> v.assertThat().isEqualTo(true));
    }

    @Test
    void anotherSprintCanBeShownAndAnUnknownOneIs404() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        String closedSprint = TestBrowser.seedId("MAR:sprint:1").toString();

        assertThat(ada.get("/api/v1/projects/MAR/board?sprint=" + closedSprint)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.sprint.name", v -> v.assertThat().isEqualTo("MAR Sprint 1"))
                .hasPathSatisfying("$.columns[3].count", v -> v.assertThat().isEqualTo(20));
        assertThat(ada.get("/api/v1/projects/MAR/board?sprint=0190f3b2-7c1e-7a51-9f00-3b1d2c9e4a10"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ada.get("/api/v1/projects/MAR/board?sprint=last")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void boardIsInvisibleOutsideTheWorkspace() {
        TestBrowser stranger = TestBrowser.signedUp(mvc, "Str", TestBrowser.uniqueEmail());

        assertThat(stranger.get("/api/v1/projects/MAR/board")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private static String contentOf(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

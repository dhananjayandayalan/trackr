package dev.trakr.api.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** "Your work" for the dev seed's Ada, plus a fresh user who has nothing yet. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HomeApiIT {

    private final MockMvcTester mvc;

    // constructor injection: Modulith's module tests verify every test class in a module's package
    @Autowired
    HomeApiIT(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void assignedTabPagesThroughEveryOpenIssueWithoutRepeats() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String page = contentOf(ada.get("/api/v1/me/work?tab=assigned" + (cursor == null ? "" : "&cursor=" + cursor)));
            List<String> keys = JsonPath.read(page, "$.items[*].key");
            List<String> categories = JsonPath.read(page, "$.items[*].statusCategory");
            assertThat(categories).doesNotContain("done");
            keys.forEach(key -> assertThat(seen.add(key)).as("%s seen twice", key).isTrue());
            cursor = JsonPath.read(page, "$.nextCursor");
            pages++;
        } while (cursor != null);

        // the tile counts the same set of issues as the tab
        int assignedOpen = JsonPath.read(contentOf(ada.get("/api/v1/me/stats")), "$.assignedOpen");
        assertThat(seen).hasSize(assignedOpen);
        assertThat(pages).isGreaterThan(1);
    }

    @Test
    void viewedAndStarredTabsAreNewestFirst() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        // the seed viewed MAR-24 most recently, then MAR-25, …
        assertThat(ada.get("/api/v1/me/work?tab=viewed")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.items.length()", v -> v.assertThat().isEqualTo(10))
                .hasPathSatisfying("$.items[0].key", v -> v.assertThat().isEqualTo("MAR-24"));
        assertThat(ada.get("/api/v1/me/work?tab=starred")).hasStatusOk().bodyJson()
                .extractingPath("$.items[*].key").asArray().containsExactlyInAnyOrder("MAR-24", "MAR-31", "BUG-1");
        assertThat(ada.get("/api/v1/me/work?tab=worked")).hasStatusOk().bodyJson()
                .extractingPath("$.items").asArray().isNotEmpty();
        assertThat(ada.get("/api/v1/me/work?tab=everything")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void statsCountInOneStatement() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        assertThat(ada.get("/api/v1/me/stats")).hasStatusOk().bodyJson()
                // Ada's open issues in a status coloured "review": MAR's In review, BUG's Verifying
                .hasPathSatisfying("$.waitingReview", v -> v.assertThat().asNumber().isNotEqualTo(0))
                .hasPathSatisfying("$.unreadMentions", v -> v.assertThat().isEqualTo(0));
    }

    @Test
    void activityShowsTheNewestEventsOfVisibleProjectsOnly() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        assertThat(ada.get("/api/v1/me/activity?limit=5")).hasStatusOk().bodyJson()
                .extractingPath("$.length()").isEqualTo(5);

        // a new user in their own workspace sees none of the seed's projects
        TestBrowser stranger = TestBrowser.signedUp(mvc, "Str", TestBrowser.uniqueEmail());
        assertThat(stranger.get("/api/v1/me/activity")).hasStatusOk().bodyJson().extractingPath("$").asArray().isEmpty();
        assertThat(stranger.get("/api/v1/me/stats")).bodyJson().extractingPath("$.assignedOpen").isEqualTo(0);
    }

    private static String contentOf(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

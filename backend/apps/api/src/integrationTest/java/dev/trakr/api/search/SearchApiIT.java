package dev.trakr.api.search;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.shared.config.TrakrProperties;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * /search end to end: TQL → Elasticsearch over the dev seed, indexed from Postgres by SearchIndexFixture.
 * Postgres is the oracle — each query's keys are compared with the same condition written in SQL, so the
 * test stays right whatever other integration tests added to the database before it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SearchApiIT {

    private static final UUID ADA = TestBrowser.seedId("user:ada");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // ProjectVisibility's rule, in SQL: a member, or a limited/open project of one of the user's workspaces
    private static final String VISIBLE = """
            (EXISTS (SELECT 1 FROM project_member pm WHERE pm.project_id = p.id AND pm.user_id = :user)
             OR (p.access IN ('limited', 'open') AND EXISTS (SELECT 1 FROM workspace_member wm
                 WHERE wm.workspace_id = p.workspace_id AND wm.user_id = :user)))""";

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final SearchIndexFixture index;

    @Autowired
    SearchApiIT(MockMvcTester mvc, JdbcClient jdbc, ElasticsearchClient elasticsearch, TrakrProperties properties) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.index = new SearchIndexFixture(elasticsearch, jdbc, properties.search().index());
    }

    @BeforeEach
    void indexTheDatabase() throws IOException {
        assertThat(index.rebuild()).isGreaterThan(250);
    }

    // BLOCK 1: the queries of 05-search §3 return exactly what Postgres says

    @Test
    void theSpecExamplesFindWhatPostgresFinds() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        LocalDate sunday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("project = MAR AND status != Done ORDER BY priority DESC, updated DESC",
                "p.key = 'MAR' AND ws.name <> 'Done'");
        cases.put("assignee = currentUser() AND sprint in openSprints()",
                "i.assignee_id = :user AND i.sprint_id IN (SELECT id FROM sprint WHERE state IN ('active', 'planned'))");
        cases.put("type in (Bug, Task) AND created >= -7d",
                "i.type IN ('bug', 'task') AND i.created_at >= now() - interval '7 days'");
        cases.put("labels in (web, seo) AND storyPoints > 3", "i.labels && ARRAY['web', 'seo'] AND i.story_points > 3");
        cases.put("priority >= High AND (flagged = true OR due <= endOfWeek())",
                "i.priority IN ('high', 'highest') AND (i.flagged OR i.due_date <= DATE '" + sunday + "')");
        cases.put("epic = MAR-1", "i.parent_id = '" + TestBrowser.seedId("MAR:issue:1") + "'");
        cases.put("project = OPS AND assignee IS EMPTY", "p.key = 'OPS' AND i.assignee_id IS NULL");
        cases.put("text ~ checkout", "i.summary ILIKE '%checkout%'");
        cases.put("status in (\"In progress\", \"in review\") AND NOT type = story",
                "ws.name IN ('In progress', 'In review') AND i.type <> 'story'");

        cases.forEach((tql, sql) -> {
            List<String> found = allKeys(ada, tql);
            assertThat(found).as(tql).isNotEmpty().containsExactlyInAnyOrderElementsOf(postgres(sql, ADA));
        });
    }

    @Test
    void orderBySortsAndKeyBreaksNoTies() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        String page = body(search(ada, "project = MAR ORDER BY priority DESC, key", 100, null));
        List<String> priorities = JsonPath.read(page, "$.items[*].priority");
        List<Integer> ranks = priorities.stream().map(p -> List.of("lowest", "low", "medium", "high", "highest")
                .indexOf(p)).toList();
        assertThat(ranks).isSortedAccordingTo((a, b) -> Integer.compare(b, a));

        // key sorts by number, not as text: OPS-2 before OPS-10
        List<String> keys = allKeys(ada, "project = OPS ORDER BY key");
        assertThat(keys.subList(0, 3)).containsExactly("OPS-1", "OPS-2", "OPS-3");
    }

    @Test
    void textMatchesAreRankedAndHighlighted() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        assertThat(search(ada, "project = MAR AND text ~ \"checkout\"", 5, null)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.items[0].highlights.summary[0]",
                        v -> v.assertThat().asString().contains("<mark>checkout</mark>"))
                .hasPathSatisfying("$.tookMs", v -> v.assertThat().asNumber().satisfies(
                        took -> assertThat(took.longValue()).isLessThan(200)));
        // typo-tolerant: one letter off still finds it
        assertThat(allKeys(ada, "text ~ chekout")).containsExactlyInAnyOrderElementsOf(allKeys(ada, "text ~ checkout"));
    }

    // BLOCK 2: pagination with point-in-time + search_after

    @Test
    void cursorsPageThroughEveryMatchOnce() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        long total;
        do {
            String page = body(search(ada, "project = MAR", 7, cursor));
            total = ((Number) JsonPath.read(page, "$.total")).longValue();
            List<String> keys = JsonPath.read(page, "$.items[*].key");
            keys.forEach(key -> assertThat(seen.add(key)).as("%s seen twice", key).isTrue());
            cursor = JsonPath.read(page, "$.nextCursor");
            pages++;
        } while (cursor != null);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(postgres("p.key = 'MAR'", ADA));
        assertThat(total).isEqualTo(seen.size());
        assertThat(pages).isGreaterThan(20);
    }

    @Test
    void aForgedCursorIsA400() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        assertThat(search(ada, "project = MAR", 10, "bm90IGEgY3Vyc29y")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // BLOCK 3: the security filter — TQL can narrow it, never widen it

    @Test
    void nobodySeesIssuesOfProjectsTheyCantAccess() {
        // a fresh account: its own empty workspace, no project of the demo
        TestBrowser stranger = TestBrowser.signedUp(mvc, "Stranger", TestBrowser.uniqueEmail());
        assertThat(total(stranger, "project = MAR")).isZero();
        assertThat(total(stranger, "")).isZero();
        assertThat(total(stranger, "project = MAR OR project != MAR")).isZero();
        // names resolve inside one's own workspaces only: Ada doesn't exist for the stranger
        assertThat(search(stranger, "assignee = \"Ada Lovelace\"", 10, null)).hasStatus(HttpStatus.BAD_REQUEST);

        // a viewer of MAR joins the demo workspace: MAR (member) and OPS (open) yes, BUG (private) no
        String email = TestBrowser.uniqueEmail();
        TestBrowser viewer = TestBrowser.signedUp(mvc, "Viewer", email);
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        assertThat(ada.post("/api/v1/projects/MAR/members", """
                {"email": "%s", "role": "viewer"}""".formatted(email))).hasStatus2xxSuccessful();

        assertThat(total(viewer, "project = BUG")).isZero();
        assertThat(allKeys(viewer, "project in (MAR, BUG)")).allMatch(key -> key.startsWith("MAR-"))
                .hasSize(postgres("p.key = 'MAR'", ADA).size());
        assertThat(allKeys(viewer, "project = BUG OR project != BUG")).noneMatch(key -> key.startsWith("BUG-"));
        assertThat(total(ada, "project = BUG")).isPositive();
    }

    // BLOCK 4: mistakes come back as problems with a position

    @Test
    void aSyntaxErrorSaysWhereAndWhatWasExpected() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        assertThat(search(ada, "project = MAR AND status = = Done", 10, null)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("TQL_SYNTAX_ERROR"))
                .hasPathSatisfying("$.position", v -> v.assertThat().isEqualTo(27))
                .hasPathSatisfying("$.expected", v -> v.assertThat().asArray().contains("name", "\"text\""));
        assertThat(search(ada, "project = MAR AND stauts = Done", 10, null)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("TQL_INVALID_QUERY"))
                .hasPathSatisfying("$.position", v -> v.assertThat().isEqualTo(18))
                .hasPathSatisfying("$.expected", v -> v.assertThat().asArray().contains("status"));
    }

    // BLOCK 5: export, autocomplete, quick search

    @Test
    void exportStreamsACsvOfEveryMatch() throws UnsupportedEncodingException {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        MvcTestResult result = ada.send(ada.mvc().post().uri("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON).content(searchBody("project = OPS ORDER BY key", null, null)));

        assertThat(result).hasStatusOk().hasHeader("Content-Disposition", "attachment; filename=\"issues.csv\"");
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        String[] lines = result.getResponse().getContentAsString().split("\r\n");
        assertThat(lines[0]).startsWith("Key,Summary,Type,Status");
        assertThat(lines[1]).startsWith("OPS-1,");
        assertThat(lines).hasSize(1 + postgres("p.key = 'OPS'", ADA).size());
    }

    @Test
    void autocompleteOffersWhatFitsAtTheCaret() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        assertThat(complete(ada, "status = ")).hasStatusOk().bodyJson()
                .extractingPath("$.expected[?(@.kind == 'value')].text").asArray()
                .contains("Done", "\"In progress\"", "Triage");
        assertThat(complete(ada, "project = MAR AND assig")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.from", v -> v.assertThat().isEqualTo(18))
                .extractingPath("$.expected[*].text").asArray().containsExactly("assignee");
        assertThat(complete(ada, "labels in (")).hasStatusOk().bodyJson()
                .extractingPath("$.expected[*].text").asArray().contains("web", "infra", "customer");
    }

    @Test
    void quickSearchFindsIssuesProjectsAndPeople() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        assertThat(ada.get("/api/v1/search/suggest?q=MAR-13")).hasStatusOk().bodyJson()
                .extractingPath("$.issues[*].key").asArray().isNotEmpty().contains("MAR-13")
                .allSatisfy(key -> assertThat((String) key).startsWith("MAR-13"));
        assertThat(ada.get("/api/v1/search/suggest?q=market")).hasStatusOk().bodyJson()
                .extractingPath("$.projects[*].key").asArray().containsExactly("MAR");
        assertThat(ada.get("/api/v1/search/suggest?q=hop")).hasStatusOk().bodyJson()
                .extractingPath("$.people[*].displayName").asArray().containsExactly("Grace Hopper");
    }

    // BLOCK 6: saved filters

    @Test
    void savedFiltersAreOwnedAndOptionallyShared() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");
        TestBrowser grace = TestBrowser.loggedIn(mvc, "grace@trakr.dev");

        assertThat(ada.post("/api/v1/filters", """
                {"name": "Broken", "tql": "type = = bug"}""")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("TQL_SYNTAX_ERROR"));

        MvcTestResult created = ada.post("/api/v1/filters", """
                {"name": "My open bugs", "tql": "type = bug AND assignee = currentUser()", "shared": true}""");
        assertThat(created).hasStatus(HttpStatus.CREATED);
        String id = JsonPath.read(body(created), "$.id");

        assertThat(grace.get("/api/v1/filters")).hasStatusOk().bodyJson()
                .extractingPath("$[?(@.id == '" + id + "')].mine").asArray().containsExactly(false);
        assertThat(grace.send(grace.mvc().patch().uri("/api/v1/filters/" + id)
                .contentType("application/merge-patch+json").content("{\"name\": \"Mine now\"}")))
                .hasStatus(HttpStatus.FORBIDDEN);

        assertThat(ada.send(ada.mvc().patch().uri("/api/v1/filters/" + id)
                .contentType("application/merge-patch+json").content("{\"starred\": true, \"shared\": false}")))
                .hasStatusOk().bodyJson()
                .hasPathSatisfying("$.starred", v -> v.assertThat().isEqualTo(true))
                .hasPathSatisfying("$.name", v -> v.assertThat().isEqualTo("My open bugs"));
        // no longer shared: for Grace it doesn't exist
        assertThat(grace.get("/api/v1/filters")).hasStatusOk().bodyJson()
                .extractingPath("$[?(@.id == '" + id + "')]").asArray().isEmpty();
        assertThat(grace.delete("/api/v1/filters/" + id)).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(ada.delete("/api/v1/filters/" + id)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ada.get("/api/v1/filters")).hasStatusOk().bodyJson()
                .extractingPath("$[?(@.id == '" + id + "')]").asArray().isEmpty();
    }

    // BLOCK 7: helpers

    private MvcTestResult search(TestBrowser browser, String tql, int limit, String cursor) {
        return browser.post("/api/v1/search", searchBody(tql, limit, cursor));
    }

    private MvcTestResult complete(TestBrowser browser, String tql) {
        return browser.send(browser.mvc().get().uri("/api/v1/search/tql/complete").param("tql", tql));
    }

    private static String searchBody(String tql, Integer limit, String cursor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tql", tql);
        body.put("limit", limit);
        body.put("cursor", cursor);
        return JSON.writeValueAsString(body);
    }

    private long total(TestBrowser browser, String tql) {
        return ((Number) JsonPath.read(body(search(browser, tql, 10, null)), "$.total")).longValue();
    }

    /** Every key a query finds, following nextCursor to the end. */
    private List<String> allKeys(TestBrowser browser, String tql) {
        List<String> keys = new ArrayList<>();
        String cursor = null;
        do {
            MvcTestResult result = search(browser, tql, 100, cursor);
            assertThat(result).as(tql).hasStatusOk();
            String page = body(result);
            keys.addAll(JsonPath.read(page, "$.items[*].key"));
            cursor = JsonPath.read(page, "$.nextCursor");
        } while (cursor != null);
        return keys;
    }

    /** The keys of live issues the user can see that match an SQL condition over i (issue), p (project), ws (status). */
    private List<String> postgres(String condition, UUID user) {
        return jdbc.sql("SELECT i.key FROM issue i JOIN project p ON p.id = i.project_id "
                        + "JOIN workflow_status ws ON ws.id = i.status_id "
                        + "WHERE i.deleted_at IS NULL AND " + VISIBLE + " AND (" + condition + ")")
                .param("user", user)
                .query(String.class)
                .list();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

package dev.trakr.api.issue;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import java.io.UnsupportedEncodingException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

// the whole app over HTTP (MockMvc): every module, Postgres and Redis
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IssueApiIT {

    private final MockMvcTester mvc;

    // constructor injection: Modulith's module tests verify every test class in a module's package
    @Autowired
    IssueApiIT(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void createReadPatchTransitionDelete() {
        TestBrowser ada = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        createProject(ada, "MAR");

        // BLOCK 1: create → 201, Location, ETag v0, key MAR-1, first todo status, reporter watches
        MvcTestResult created = createIssue(ada, """
                {"projectKey": "MAR", "type": "story", "summary": "Log in with GitHub", "priority": "high",
                 "storyPoints": 3, "labels": ["auth", "auth", "ui"]}""");
        assertThat(created).hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "/api/v1/issues/MAR-1")
                .hasHeader("ETag", "\"v0\"")
                .bodyJson()
                .hasPathSatisfying("$.key", v -> v.assertThat().isEqualTo("MAR-1"))
                .hasPathSatisfying("$.status.name", v -> v.assertThat().isEqualTo("To do"))
                .hasPathSatisfying("$.priority", v -> v.assertThat().isEqualTo("high"))
                .hasPathSatisfying("$.storyPoints", v -> v.assertThat().isEqualTo(3.0))
                .hasPathSatisfying("$.labels", v -> v.assertThat().asArray().containsExactly("auth", "ui"))
                .hasPathSatisfying("$.reporter.displayName", v -> v.assertThat().isEqualTo("Ada"))
                .hasPathSatisfying("$.watching", v -> v.assertThat().isEqualTo(true))
                .hasPathSatisfying("$.watcherCount", v -> v.assertThat().isEqualTo(1));
        assertThat(createIssue(ada, """
                {"projectKey": "MAR", "type": "task", "summary": "Second"}""")).bodyJson()
                .extractingPath("$.key").isEqualTo("MAR-2");

        // BLOCK 2: read
        assertThat(ada.get("/api/v1/issues/MAR-1")).hasStatusOk().hasHeader("ETag", "\"v0\"");

        // BLOCK 3: patch — needs If-Match; a stale one is 412 with the current issue
        assertThat(patch(ada, "MAR-1", null, "{\"summary\": \"x\"}")).hasStatus(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(patch(ada, "MAR-1", "\"v0\"", """
                {"summary": "Log in with GitHub or Google", "dueDate": "2026-12-01"}"""))
                .hasStatusOk()
                .hasHeader("ETag", "\"v1\"")
                .bodyJson()
                .hasPathSatisfying("$.summary", v -> v.assertThat().isEqualTo("Log in with GitHub or Google"))
                .hasPathSatisfying("$.dueDate", v -> v.assertThat().isEqualTo("2026-12-01"));
        assertThat(patch(ada, "MAR-1", "\"v0\"", "{\"priority\": \"low\"}"))
                .hasStatus(HttpStatus.PRECONDITION_FAILED)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("VERSION_MISMATCH"))
                .hasPathSatisfying("$.current.summary", v -> v.assertThat().isEqualTo("Log in with GitHub or Google"));
        // merge patch: null clears
        assertThat(patch(ada, "MAR-1", "\"v1\"", "{\"dueDate\": null}")).hasStatusOk()
                .bodyJson().hasPathSatisfying("$.dueDate", v -> v.assertThat().isNull());
        assertThat(patch(ada, "MAR-1", "\"v2\"", "{\"summary\": \"\", \"status\": \"done\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactlyInAnyOrder("summary", "status");

        // BLOCK 4: transition — allowed targets, then a move to Done with the expected version in the body
        String doneId = statusId(ada, "MAR-1", "Done");
        assertThat(ada.post("/api/v1/issues/MAR-1/transition", """
                {"toStatusId": "%s", "expectedVersion": 1}""".formatted(doneId)))
                .hasStatus(HttpStatus.PRECONDITION_FAILED);
        assertThat(ada.post("/api/v1/issues/MAR-1/transition", """
                {"toStatusId": "%s", "expectedVersion": 2}""".formatted(doneId)))
                .hasStatusOk()
                .hasHeader("ETag", "\"v3\"")
                .bodyJson()
                .hasPathSatisfying("$.status.category", v -> v.assertThat().isEqualTo("done"))
                .hasPathSatisfying("$.resolvedAt", v -> v.assertThat().isNotNull());

        // BLOCK 5: history, newest first
        assertThat(ada.get("/api/v1/issues/MAR-1/history")).hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.items[*].kind", v -> v.assertThat().asArray().containsExactly(
                        "transitioned", "field_changed", "field_changed", "field_changed", "created"))
                .hasPathSatisfying("$.items[0].newValue.name", v -> v.assertThat().isEqualTo("Done"))
                .hasPathSatisfying("$.items[1].field", v -> v.assertThat().isEqualTo("dueDate"))
                .hasPathSatisfying("$.items[1].oldValue", v -> v.assertThat().isEqualTo("2026-12-01"))
                .hasPathSatisfying("$.items[4].actor.displayName", v -> v.assertThat().isEqualTo("Ada"))
                .hasPathSatisfying("$.nextCursor", v -> v.assertThat().isNull());

        // BLOCK 6: delete — If-Match again; afterwards the key is gone, the number is never reused
        assertThat(delete(ada, "MAR-1", "\"v2\"")).hasStatus(HttpStatus.PRECONDITION_FAILED);
        assertThat(delete(ada, "MAR-1", "\"v3\"")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ada.get("/api/v1/issues/MAR-1")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(createIssue(ada, """
                {"projectKey": "MAR", "type": "task", "summary": "Third"}""")).bodyJson()
                .extractingPath("$.key").isEqualTo("MAR-3");
    }

    @Test
    void invisibleProjectsAre404() {
        TestBrowser ada = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        createProject(ada, "SEC");
        createIssue(ada, """
                {"projectKey": "SEC", "type": "task", "summary": "Secret"}""");
        TestBrowser stranger = TestBrowser.signedUp(mvc, "Str", uniqueEmail());

        // the same answer as a key that doesn't exist: nobody learns which private projects exist
        assertThat(stranger.get("/api/v1/issues/SEC-1")).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
        assertThat(stranger.get("/api/v1/issues/SEC-99")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(stranger.get("/api/v1/issues/not-a-key")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(createIssue(stranger, """
                {"projectKey": "SEC", "type": "task", "summary": "Mine"}""")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewersReadButCannotEdit() {
        TestBrowser ada = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        String vicEmail = uniqueEmail();
        TestBrowser vic = TestBrowser.signedUp(mvc, "Vic", vicEmail);
        createProject(ada, "VWR");
        ada.post("/api/v1/projects/VWR/members", """
                {"email": "%s", "role": "viewer"}""".formatted(vicEmail));
        createIssue(ada, """
                {"projectKey": "VWR", "type": "task", "summary": "Read only"}""");
        String vicId = JsonPath.read(contentOf(vic.get("/api/v1/me")), "$.id");

        assertThat(vic.get("/api/v1/issues/VWR-1")).hasStatusOk();
        assertThat(patch(vic, "VWR-1", "\"v0\"", "{\"summary\": \"Mine\"}")).hasStatus(HttpStatus.FORBIDDEN);
        // a viewer can't be assigned either
        assertThat(patch(ada, "VWR-1", "\"v0\"", "{\"assigneeId\": \"%s\"}".formatted(vicId)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("assigneeId");
        // watching is personal: viewers may
        assertThat(vic.put("/api/v1/issues/VWR-1/watch")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(vic.get("/api/v1/issues/VWR-1")).bodyJson()
                .hasPathSatisfying("$.watching", v -> v.assertThat().isEqualTo(true))
                .hasPathSatisfying("$.watcherCount", v -> v.assertThat().isEqualTo(2));
    }

    @Test
    void parentRulesAndSubtaskDeletion() {
        TestBrowser ada = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        createProject(ada, "PAR");
        createIssue(ada, """
                {"projectKey": "PAR", "type": "epic", "summary": "Epic"}""");
        createIssue(ada, """
                {"projectKey": "PAR", "type": "story", "summary": "Story", "parentKey": "PAR-1"}""");

        assertThat(createIssue(ada, """
                {"projectKey": "PAR", "type": "subtask", "summary": "Orphan"}"""))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_PARENT");
        assertThat(createIssue(ada, """
                {"projectKey": "PAR", "type": "story", "summary": "Under a story", "parentKey": "PAR-2"}"""))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(createIssue(ada, """
                {"projectKey": "PAR", "type": "subtask", "summary": "Sub", "parentKey": "PAR-2"}"""))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.parent.key").isEqualTo("PAR-2");

        assertThat(ada.get("/api/v1/issues/PAR-2")).bodyJson()
                .hasPathSatisfying("$.parent.key", v -> v.assertThat().isEqualTo("PAR-1"))
                .hasPathSatisfying("$.subtasks[*].key", v -> v.assertThat().asArray().containsExactly("PAR-3"));

        // deleting the story takes its subtask along; the epic stays
        assertThat(delete(ada, "PAR-2", "\"v0\"")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ada.get("/api/v1/issues/PAR-3")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ada.get("/api/v1/issues/PAR-1")).hasStatusOk()
                .bodyJson().extractingPath("$.subtasks").asArray().isEmpty();
    }

    @Test
    void linksWorklogsAndStars() {
        TestBrowser ada = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        createProject(ada, "LNK");
        createIssue(ada, """
                {"projectKey": "LNK", "type": "task", "summary": "Blocker"}""");
        createIssue(ada, """
                {"projectKey": "LNK", "type": "task", "summary": "Blocked"}""");

        // BLOCK 1: one row, read from both ends
        MvcTestResult link = ada.post("/api/v1/issues/LNK-1/links", """
                {"targetKey": "LNK-2", "type": "blocks"}""");
        assertThat(link).hasStatus(HttpStatus.CREATED).bodyJson()
                .hasPathSatisfying("$.label", v -> v.assertThat().isEqualTo("blocks"))
                .hasPathSatisfying("$.issue.key", v -> v.assertThat().isEqualTo("LNK-2"));
        assertThat(ada.get("/api/v1/issues/LNK-2")).bodyJson()
                .hasPathSatisfying("$.links[0].label", v -> v.assertThat().isEqualTo("is blocked by"))
                .hasPathSatisfying("$.links[0].issue.key", v -> v.assertThat().isEqualTo("LNK-1"));
        // linking bumped the source's version (new ETag), not the target's
        assertThat(ada.get("/api/v1/issues/LNK-1")).hasHeader("ETag", "\"v1\"");
        assertThat(ada.post("/api/v1/issues/LNK-1/links", """
                {"targetKey": "LNK-2", "type": "blocks"}"""))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("LINK_EXISTS");
        assertThat(ada.post("/api/v1/issues/LNK-1/links", """
                {"targetKey": "LNK-1", "type": "relates"}""")).hasStatus(HttpStatus.BAD_REQUEST);
        String linkId = JsonPath.read(contentOf(link), "$.id");
        assertThat(ada.delete("/api/v1/issue-links/" + linkId)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ada.get("/api/v1/issues/LNK-2")).bodyJson().extractingPath("$.links").asArray().isEmpty();

        // BLOCK 2: work log adds to time spent
        assertThat(ada.post("/api/v1/issues/LNK-2/worklogs", """
                {"startedAt": "2026-11-07T09:00:00Z", "minutes": 90, "note": "pairing"}"""))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.minutes").isEqualTo(90);
        assertThat(ada.get("/api/v1/issues/LNK-2/worklogs")).bodyJson()
                .extractingPath("$[*].note").asArray().containsExactly("pairing");
        assertThat(ada.get("/api/v1/issues/LNK-2")).bodyJson()
                .extractingPath("$.timeTracking.timeSpentMin").isEqualTo(90);

        // BLOCK 3: star is personal and idempotent
        assertThat(ada.put("/api/v1/issues/LNK-2/star")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ada.put("/api/v1/issues/LNK-2/star")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ada.get("/api/v1/issues/LNK-2")).bodyJson().extractingPath("$.starred").isEqualTo(true);
    }

    @Test
    void moveAndBulkOverHttp() {
        TestBrowser ada = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        createProject(ada, "BLK");
        for (String summary : List.of("One", "Two", "Three")) {
            createIssue(ada, """
                    {"projectKey": "BLK", "type": "task", "summary": "%s"}""".formatted(summary));
        }
        TestBrowser stranger = TestBrowser.signedUp(mvc, "Str", uniqueEmail());
        createProject(stranger, "HID");
        createIssue(stranger, """
                {"projectKey": "HID", "type": "task", "summary": "Not yours"}""");

        // BLOCK 1: move — BLK-3 to the top of the backlog, above BLK-1
        assertThat(ada.post("/api/v1/issues/BLK-3/move", """
                {"afterKey": "BLK-1", "expectedVersion": 0}"""))
                .hasStatusOk()
                .hasHeader("ETag", "\"v1\"")
                .bodyJson()
                .hasPathSatisfying("$.warnings", v -> v.assertThat().asArray().isEmpty())
                .hasPathSatisfying("$.issue.key", v -> v.assertThat().isEqualTo("BLK-3"));
        // null neighbours and status = not given (the OpenAPI document says nullable; generated clients send null)
        assertThat(ada.post("/api/v1/issues/BLK-3/move", """
                {"toStatusId": null, "beforeKey": null, "afterKey": "BLK-1", "expectedVersion": 1}"""))
                .hasStatusOk()
                .bodyJson().extractingPath("$.issue.key").isEqualTo("BLK-3");
        assertThat(ada.post("/api/v1/issues/BLK-3/move", """
                {"toSprintId": "next week", "expectedVersion": 1}"""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("toSprintId");

        // BLOCK 2: bulk — one result per key; the invisible issue fails alone
        String done = statusId(ada, "BLK-1", "Done");
        assertThat(ada.post("/api/v1/issues/bulk", """
                {"keys": ["BLK-1", "BLK-2", "HID-1"], "action": "transition", "args": {"statusId": "%s"}}"""
                .formatted(done)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[*].ok", v -> v.assertThat().asArray().containsExactly(true, true, false))
                .hasPathSatisfying("$[0].version", v -> v.assertThat().isEqualTo(1))
                .hasPathSatisfying("$[2].code", v -> v.assertThat().isEqualTo("NOT_FOUND"));
        assertThat(ada.get("/api/v1/issues/BLK-2")).bodyJson()
                .extractingPath("$.status.name").isEqualTo("Done");
    }

    @Test
    void openApiDocumentNamesTheIssueOperations() {
        assertThat(new TestBrowser(mvc).get("/v3/api-docs")).hasStatusOk()
                .bodyJson().extractingPath("$.paths..operationId").asArray()
                .contains("createIssue", "getIssue", "updateIssue", "deleteIssue", "transitionIssue",
                        "listIssueTransitions", "getIssueHistory", "linkIssue", "unlinkIssue", "watchIssue",
                        "starIssue", "logWork", "listWorklogs", "moveIssue", "bulkIssues", "getBoard",
                        "getBacklog", "createSprint", "startSprint", "completeSprint", "getMyWork", "getMyStats");
    }

    private void createProject(TestBrowser browser, String key) {
        assertThat(browser.post("/api/v1/projects", """
                {"name": "Project %s", "key": "%s", "template": "scrum", "access": "private", "color": "cyan"}"""
                .formatted(key, key))).hasStatus(HttpStatus.CREATED);
    }

    private static MvcTestResult createIssue(TestBrowser browser, String json) {
        return browser.post("/api/v1/issues", json);
    }

    private static String statusId(TestBrowser browser, String issueKey, String name) {
        List<String> ids = JsonPath.read(contentOf(browser.get("/api/v1/issues/" + issueKey + "/transitions")),
                "$[?(@.name == '" + name + "')].id");
        return ids.getFirst();
    }

    private static MvcTestResult patch(TestBrowser browser, String key, @Nullable String ifMatch, String json) {
        MockMvcTester.MockMvcRequestBuilder request = browser.mvc().patch().uri("/api/v1/issues/" + key)
                .contentType("application/merge-patch+json").content(json);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return browser.send(request);
    }

    private static MvcTestResult delete(TestBrowser browser, String key, String ifMatch) {
        return browser.send(browser.mvc().delete().uri("/api/v1/issues/" + key).header("If-Match", ifMatch));
    }

    private static String contentOf(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

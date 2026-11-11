package dev.trakr.api.workflow;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.kernel.Ids;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** PUT /projects/{key}/workflow end to end: the version, the remap of deleted statuses, the board following. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WorkflowApiIT {

    private final MockMvcTester mvc;

    @Autowired
    WorkflowApiIT(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void replaceRenamesAddsDeletesWithRemapAndTheBoardFollows() throws Exception {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        project(owner, "WFL");
        MvcTestResult first = owner.get("/api/v1/projects/WFL/workflow");
        assertThat(first).hasStatusOk().hasHeader("ETag", "\"v0\"");
        Map<String, String> ids = statusIds(first);
        String issueKey = issueIn(owner, "WFL");
        move(owner, issueKey, ids.get("In review"));

        // BLOCK 1: deleting "In review" while an issue is in it, without saying where it goes → 409 STATUS_IN_USE
        String qa = Ids.newId().toString();
        String statuses = """
                [{"id": "%s", "name": "Backlog", "category": "todo"},
                 {"id": "%s", "name": "Doing", "category": "in_progress", "wipLimit": 3},
                 {"id": "%s", "name": "QA", "category": "in_progress", "color": "review"},
                 {"id": "%s", "name": "Done", "category": "done"}]"""
                .formatted(ids.get("To do"), ids.get("In progress"), qa, ids.get("Done"));
        String transitions = """
                [{"fromStatusId": null, "toStatusId": "%s"}, {"fromStatusId": "%s", "toStatusId": "%s"},
                 {"fromStatusId": "%s", "toStatusId": "%s"}]"""
                .formatted(ids.get("To do"), ids.get("To do"), ids.get("In progress"), ids.get("In progress"), qa);
        assertThat(put(owner, "WFL", "\"v0\"", """
                {"statuses": %s, "transitions": %s}""".formatted(statuses, transitions)))
                .hasStatus(HttpStatus.CONFLICT).bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("STATUS_IN_USE"));

        // BLOCK 2: with a remap it saves, as version 1
        MvcTestResult saved = put(owner, "WFL", "\"v0\"", """
                {"statuses": %s, "transitions": %s, "remapTo": {"%s": "%s"}}"""
                .formatted(statuses, transitions, ids.get("In review"), qa));
        assertThat(saved).hasStatusOk().hasHeader("ETag", "\"v1\"").bodyJson()
                .hasPathSatisfying("$.statuses[*].name", v -> v.assertThat().asArray()
                        .containsExactly("Backlog", "Doing", "QA", "Done"))
                .hasPathSatisfying("$.transitions.length()", v -> v.assertThat().isEqualTo(3));

        // the issue moved to QA, with a new version; the board renamed, dropped and added columns
        assertThat(owner.get("/api/v1/issues/" + issueKey)).bodyJson()
                .hasPathSatisfying("$.status.name", v -> v.assertThat().isEqualTo("QA"))
                .hasPathSatisfying("$.version", v -> v.assertThat().isEqualTo(2));
        String project = owner.get("/api/v1/projects/WFL").getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(project, "$.board.columns[*].name"))
                .containsExactly("Backlog", "Doing", "Done", "QA");
        assertThat(JsonPath.<List<Object>>read(project, "$.board.columns[?(@.name == 'Doing')].wipLimit"))
                .containsExactly(3);

        // BLOCK 3: the old version again → 412 with the current workflow
        assertThat(put(owner, "WFL", "\"v0\"", """
                {"statuses": %s, "transitions": []}""".formatted(statuses)))
                .hasStatus(HttpStatus.PRECONDITION_FAILED).bodyJson()
                .hasPathSatisfying("$.current.version", v -> v.assertThat().isEqualTo(1));

        // the new rules hold: QA → Backlog isn't a transition any more
        assertThat(owner.get("/api/v1/issues/" + issueKey + "/transitions")).bodyJson()
                .hasPathSatisfying("$[*].name", v -> v.assertThat().asArray().containsExactly("Backlog"));
    }

    @Test
    void invalidWorkflowsAreRejectedFieldByField() {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        project(owner, "WFV");
        String a = Ids.newId().toString();
        String b = Ids.newId().toString();

        assertThat(put(owner, "WFV", "\"v0\"", """
                {"statuses": [{"id": "%s", "name": "Same", "category": "in_progress"},
                              {"id": "%s", "name": "same", "category": "done", "wipLimit": 0}],
                 "transitions": []}""".formatted(a, b)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .hasPathSatisfying("$.errors[*].field", v -> v.assertThat().asArray()
                        .contains("statuses[1].name", "statuses[1].wipLimit", "statuses"));
        assertThat(put(owner, "WFV", null, "{\"statuses\": [], \"transitions\": []}"))
                .hasStatus(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    void onlyAdminsMayReplaceIt() {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        String memberEmail = uniqueEmail();
        TestBrowser member = TestBrowser.signedUp(mvc, "Grace", memberEmail);
        project(owner, "WFA");
        assertThat(owner.post("/api/v1/projects/WFA/members", """
                {"email": "%s", "role": "member"}""".formatted(memberEmail))).hasStatus(HttpStatus.CREATED);

        assertThat(member.get("/api/v1/projects/WFA/workflow")).hasStatusOk();
        assertThat(put(member, "WFA", "\"v0\"", "{\"statuses\": [], \"transitions\": []}")).hasStatus(HttpStatus.FORBIDDEN);
    }

    private static void project(TestBrowser owner, String key) {
        assertThat(owner.post("/api/v1/projects", """
                {"name": "Project %s", "key": "%s", "template": "scrum", "access": "private", "color": "cyan"}"""
                .formatted(key, key))).hasStatus(HttpStatus.CREATED);
    }

    private static String issueIn(TestBrowser owner, String key) throws Exception {
        MvcTestResult created = owner.post("/api/v1/issues", """
                {"projectKey": "%s", "type": "task", "summary": "Moves around"}""".formatted(key));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(created.getResponse().getContentAsString(), "$.key");
    }

    private static void move(TestBrowser owner, String issueKey, String statusId) {
        assertThat(owner.post("/api/v1/issues/" + issueKey + "/transition", """
                {"toStatusId": "%s", "expectedVersion": 0}""".formatted(statusId))).hasStatusOk();
    }

    private static Map<String, String> statusIds(MvcTestResult workflow) throws Exception {
        String body = workflow.getResponse().getContentAsString();
        List<String> names = JsonPath.read(body, "$.statuses[*].name");
        List<String> ids = JsonPath.read(body, "$.statuses[*].id");
        Map<String, String> byName = new java.util.LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            byName.put(names.get(i), ids.get(i));
        }
        return byName;
    }

    private MvcTestResult put(TestBrowser browser, String key, @Nullable String ifMatch, String json) {
        MockMvcTester.MockMvcRequestBuilder request = mvc.put().uri("/api/v1/projects/" + key + "/workflow")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return browser.send(request);
    }
}

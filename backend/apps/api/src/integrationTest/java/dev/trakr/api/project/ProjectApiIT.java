package dev.trakr.api.project;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.kernel.Ids;
import java.io.UnsupportedEncodingException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

// the whole app over HTTP (MockMvc), with every module, Postgres and Redis
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProjectApiIT {

    @Autowired
    MockMvcTester mvc;

    @ParameterizedTest(name = "{0}: {1} statuses, {2} columns")
    @CsvSource({
            "scrum,  4, 4, To do",
            "kanban, 4, 3, Selected",
            "bugs,   5, 4, Confirmed",
            "blank,  3, 3, To do"})
    void eachTemplateSeedsItsWorkflowAndBoard(String template, int statuses, int columns, String firstColumn) {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        assertThat(create(owner, "TPL", template, "private")).hasStatus(HttpStatus.CREATED);

        MvcTestResult project = owner.get("/api/v1/projects/TPL");

        assertThat(project).hasStatusOk();
        assertThat(project.getResponse().getHeader("ETag")).isEqualTo("\"v0\"");
        assertThat(project).bodyJson()
                .hasPathSatisfying("$.key", key -> key.assertThat().isEqualTo("TPL"))
                .hasPathSatisfying("$.myRole", role -> role.assertThat().isEqualTo("admin"))
                .hasPathSatisfying("$.statuses.length()", n -> n.assertThat().isEqualTo(statuses))
                // every status → every other status
                .hasPathSatisfying("$.transitions.length()", n -> n.assertThat().isEqualTo(statuses * (statuses - 1)))
                .hasPathSatisfying("$.board.columns.length()", n -> n.assertThat().isEqualTo(columns))
                .hasPathSatisfying("$.board.columns[0].name", name -> name.assertThat().isEqualTo(firstColumn));
    }

    @Test
    void duplicateKeyIs409ProjectKeyTaken() {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        create(owner, "DUP", "scrum", "private");

        assertThat(create(owner, "DUP", "kanban", "open"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("PROJECT_KEY_TAKEN");
    }

    @Test
    void patchNeedsTheCurrentETag() {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        create(owner, "ETG", "scrum", "private");

        assertThat(patch(owner, "ETG", null, "{\"name\": \"Renamed\"}"))
                .hasStatus(HttpStatus.PRECONDITION_REQUIRED);

        MvcTestResult renamed = patch(owner, "ETG", "\"v0\"", """
                {"name": "Renamed", "description": "first"}""");
        assertThat(renamed).hasStatusOk().bodyJson().extractingPath("$.name").isEqualTo("Renamed");
        assertThat(renamed.getResponse().getHeader("ETag")).isEqualTo("\"v1\"");

        // someone else saved v1 meanwhile: 412, and the current project to merge with
        assertThat(patch(owner, "ETG", "\"v0\"", "{\"color\": \"pink\"}"))
                .hasStatus(HttpStatus.PRECONDITION_FAILED)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> code.assertThat().isEqualTo("VERSION_MISMATCH"))
                .hasPathSatisfying("$.current.name", name -> name.assertThat().isEqualTo("Renamed"));

        // merge patch: null clears, absent fields stay
        assertThat(patch(owner, "ETG", "\"v1\"", "{\"description\": null}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.description", description -> description.assertThat().isNull())
                .hasPathSatisfying("$.name", name -> name.assertThat().isEqualTo("Renamed"));
    }

    @Test
    void viewerCanReadButNotPatch() {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        String viewerEmail = uniqueEmail();
        TestBrowser viewer = TestBrowser.signedUp(mvc, "Vic", viewerEmail);
        create(owner, "VIE", "scrum", "private");

        assertThat(owner.post("/api/v1/projects/VIE/members", """
                {"email": "%s", "role": "viewer"}""".formatted(viewerEmail))).hasStatus(HttpStatus.CREATED);

        assertThat(viewer.get("/api/v1/projects/VIE")).hasStatusOk()
                .bodyJson().extractingPath("$.myRole").isEqualTo("viewer");
        assertThat(patch(viewer, "VIE", "\"v0\"", "{\"name\": \"Mine now\"}"))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void accessLevelDecidesWhatOtherWorkspaceMembersSee() {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        String colleagueEmail = uniqueEmail();
        TestBrowser colleague = TestBrowser.signedUp(mvc, "Col", colleagueEmail);
        create(owner, "PRV", "blank", "private");
        create(owner, "LIM", "blank", "limited");
        create(owner, "OPN", "blank", "open");
        // adding someone to a project also adds them to the workspace; removing them keeps that
        owner.post("/api/v1/projects/OPN/members", """
                {"email": "%s", "role": "member"}""".formatted(colleagueEmail));
        String colleagueId = JsonPath.read(contentOf(colleague.get("/api/v1/me")), "$.id");
        assertThat(owner.delete("/api/v1/projects/OPN/members/" + colleagueId)).hasStatus(HttpStatus.NO_CONTENT);

        // private: not even its existence leaks — the same 404 as a key that doesn't exist
        assertThat(colleague.get("/api/v1/projects/PRV")).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
        assertThat(colleague.get("/api/v1/projects/LIM")).hasStatusOk()
                .bodyJson().extractingPath("$.myRole").isEqualTo("viewer");
        assertThat(colleague.get("/api/v1/projects/OPN")).hasStatusOk()
                .bodyJson().extractingPath("$.myRole").isEqualTo("member");
        assertThat(colleague.get("/api/v1/projects")).hasStatusOk()
                .bodyJson().extractingPath("$[*].key").asArray().containsExactlyInAnyOrder("LIM", "OPN");

        // someone from another workspace sees nothing at all
        TestBrowser stranger = TestBrowser.signedUp(mvc, "Str", uniqueEmail());
        assertThat(stranger.get("/api/v1/projects/OPN")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void idempotencyKeyReplaysTheFirstResponse() {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        String key = Ids.newId().toString();

        MvcTestResult first = owner.send(createRequest(owner, "IDM").header("Idempotency-Key", key));
        MvcTestResult retry = owner.send(createRequest(owner, "IDM").header("Idempotency-Key", key));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        // not 409 PROJECT_KEY_TAKEN: the retry gets the first answer back, nothing runs twice
        assertThat(retry).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry.getResponse().getHeader("Location")).isEqualTo("/api/v1/projects/IDM");
        assertThat(JsonPath.<String>read(contentOf(retry), "$.id")).isEqualTo(JsonPath.read(contentOf(first), "$.id"));
    }

    @Test
    void listFiltersStarsAndArchives() {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        create(owner, "ALP", "scrum", "private");
        create(owner, "BET", "kanban", "private");

        assertThat(owner.put("/api/v1/projects/BET/star")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(owner.get("/api/v1/projects?starred=true"))
                .bodyJson().extractingPath("$[*].key").asArray().containsExactly("BET");
        assertThat(owner.get("/api/v1/projects?template=scrum"))
                .bodyJson().extractingPath("$[*].key").asArray().containsExactly("ALP");
        assertThat(owner.get("/api/v1/projects?q=bet"))
                .bodyJson().extractingPath("$[*].key").asArray().containsExactly("BET");

        assertThat(owner.post("/api/v1/projects/ALP/archive", "")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(owner.get("/api/v1/projects"))
                .bodyJson().extractingPath("$[*].key").asArray().containsExactly("BET");
    }

    @Test
    void keySuggestionSkipsTakenKeys() {
        TestBrowser owner = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());

        assertThat(owner.get("/api/v1/projects/key-suggestion?name=Marketing%20Campaign"))
                .bodyJson().extractingPath("$.key").isEqualTo("MC");
        create(owner, "MC", "scrum", "private");
        assertThat(owner.get("/api/v1/projects/key-suggestion?name=Marketing%20Campaign"))
                .bodyJson().extractingPath("$.key").isEqualTo("MAR");
    }

    @Test
    void openApiDocumentNamesEveryOperation() {
        TestBrowser browser = new TestBrowser(mvc);

        assertThat(browser.get("/v3/api-docs")).hasStatusOk()
                .bodyJson().extractingPath("$.paths..operationId").asArray()
                .contains("listProjects", "createProject", "getProject", "updateProject", "addProjectMember",
                        "signUp", "login", "getMe");
    }

    private MvcTestResult create(TestBrowser browser, String key, String template, String access) {
        return browser.post("/api/v1/projects", projectJson(key, template, access));
    }

    private MockMvcTester.MockMvcRequestBuilder createRequest(TestBrowser browser, String key) {
        return browser.mvc().post().uri("/api/v1/projects").contentType(MediaType.APPLICATION_JSON)
                .content(projectJson(key, "scrum", "private"));
    }

    private static String projectJson(String key, String template, String access) {
        return """
                {"name": "Project %s", "key": "%s", "template": "%s", "access": "%s", "color": "cyan"}"""
                .formatted(key, key, template, access);
    }

    private MvcTestResult patch(TestBrowser browser, String key, @Nullable String ifMatch, String json) {
        MockMvcTester.MockMvcRequestBuilder request = browser.mvc().patch().uri("/api/v1/projects/" + key)
                .contentType("application/merge-patch+json").content(json);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return browser.send(request);
    }

    private static String contentOf(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

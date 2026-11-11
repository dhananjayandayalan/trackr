package dev.trakr.api.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import java.io.UnsupportedEncodingException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The reports over the dev seed (R__dev_seed_reports.sql), with the clock fixed on Tuesday 10 November 2026: the
 * burndown must give exactly the numbers worked out by hand at the top of that file.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportApiIT {

    private static final String SPRINT_1 = TestBrowser.seedId("MAR:sprint:1").toString();
    private static final String SPRINT_2 = TestBrowser.seedId("MAR:sprint:2").toString();

    // replaces the app's Clock bean in this test's context: "today" is the 10th whenever the test runs
    @TestBean
    Clock clock;

    static Clock clock() {
        return Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);
    }

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;

    @Autowired
    ReportApiIT(MockMvcTester mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @Test
    void burndownOfTheActiveSprintMatchesTheHandComputedNumbers() throws Exception {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        MvcTestResult result = ada.get("/api/v1/projects/MAR/reports/burndown");

        assertThat(result).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.sprintId", v -> v.assertThat().isEqualTo(SPRINT_2))
                .hasPathSatisfying("$.committed", v -> v.assertThat().asNumber().isEqualTo(79.0));
        String body = result.getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.days[*].day")).containsExactly("2026-11-02", "2026-11-03",
                "2026-11-04", "2026-11-05", "2026-11-06", "2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12",
                "2026-11-13", "2026-11-16");
        assertThat(numbers(body, "$.days[*].ideal"))
                .containsExactly(79.0, 71.1, 63.2, 55.3, 47.4, 39.5, 31.6, 23.7, 15.8, 7.9, 0.0);
        // up to today; the days after it have no value yet
        assertThat(JsonPath.<List<Object>>read(body, "$.days[*].remaining"))
                .map(value -> value == null ? null : ((Number) value).doubleValue())
                .containsExactly(79.0, 79.0, 74.0, 72.0, 72.0, 67.0, 65.0, null, null, null, null);
        assertThat(JsonPath.<List<Object>>read(body, "$.days[*].scopeChange")).containsOnlyNulls();
    }

    @Test
    void closedSprintBurnsDownToZeroAndShowsInVelocity() throws Exception {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        String burndown = ada.get("/api/v1/projects/MAR/reports/burndown?sprintId=" + SPRINT_1)
                .getResponse().getContentAsString();
        assertThat(numbers(burndown, "$.days[*].remaining")).hasSize(11).startsWith(65.0).endsWith(0.0);

        assertThat(ada.get("/api/v1/projects/MAR/reports/velocity")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.length()", v -> v.assertThat().isEqualTo(1))
                .hasPathSatisfying("$[0].name", v -> v.assertThat().isEqualTo("MAR Sprint 1"))
                .hasPathSatisfying("$[0].committed", v -> v.assertThat().asNumber().isEqualTo(65.0))
                .hasPathSatisfying("$[0].completed", v -> v.assertThat().asNumber().isEqualTo(65.0));
    }

    @Test
    void sprintReportOfAClosedSprintReadsWhatWasStored() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        assertThat(ada.get("/api/v1/projects/MAR/reports/sprint?sprintId=" + SPRINT_1)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.state", v -> v.assertThat().isEqualTo("closed"))
                .hasPathSatisfying("$.completed.length()", v -> v.assertThat().isEqualTo(20))
                .hasPathSatisfying("$.completed[0].key", v -> v.assertThat().isEqualTo("MAR-4"))
                .hasPathSatisfying("$.notCompleted.length()", v -> v.assertThat().isEqualTo(0))
                .hasPathSatisfying("$.added.length()", v -> v.assertThat().isEqualTo(0));
    }

    @Test
    void sprintReportOfTheActiveSprintIsComputedFromTheIssuesNow() throws Exception {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        String report = ada.get("/api/v1/projects/MAR/reports/sprint").getResponse().getContentAsString();

        // other tests may move MAR issues: compare with the issue table instead of fixed numbers
        Map<String, Object> now = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status_category = 'done') AS done,
                               count(*) FILTER (WHERE status_category <> 'done') AS open
                        FROM issue WHERE sprint_id = ?::uuid AND deleted_at IS NULL""")
                .params(SPRINT_2).query().singleRow();
        assertThat(JsonPath.<List<?>>read(report, "$.completed")).hasSize(((Number) now.get("done")).intValue());
        assertThat(JsonPath.<List<?>>read(report, "$.notCompleted")).hasSize(((Number) now.get("open")).intValue());
        // MAR-50 … MAR-53 joined after the start (seed block 8)
        assertThat(JsonPath.<List<String>>read(report, "$.added[*].key")).contains("MAR-50", "MAR-51", "MAR-52", "MAR-53");
        assertThat(((Number) JsonPath.read(report, "$.committedPoints")).doubleValue()).isEqualTo(79.0);
    }

    @Test
    void breakdownCoversEveryStatusOfTheSprint() throws Exception {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        String breakdown = ada.get("/api/v1/projects/MAR/reports/breakdown").getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(breakdown, "$.sprintId")).isEqualTo(SPRINT_2);
        assertThat(JsonPath.<List<String>>read(breakdown, "$.byStatus[*].name"))
                .containsExactly("To do", "In progress", "In review", "Done");
        int inSprint = jdbc.sql("SELECT count(*) FROM issue WHERE sprint_id = ?::uuid AND deleted_at IS NULL")
                .params(SPRINT_2).query(Integer.class).single();
        assertThat(JsonPath.<List<Integer>>read(breakdown, "$.byStatus[*].issues").stream().mapToInt(i -> i).sum())
                .isEqualTo(inSprint);
        assertThat(JsonPath.<List<String>>read(breakdown, "$.byAssignee[*].name")).contains("Ada Lovelace");
    }

    @Test
    void anotherProjectsSprintIs404AndKanbanHasNoBurndown() {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        assertThat(ada.get("/api/v1/projects/OPS/reports/burndown?sprintId=" + SPRINT_2))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ada.get("/api/v1/projects/OPS/reports/burndown")).hasStatus(HttpStatus.NOT_FOUND);
        // without sprints the breakdown is the whole project
        assertThat(ada.get("/api/v1/projects/OPS/reports/breakdown")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.sprintId", v -> v.assertThat().isNull());
    }

    private static List<Double> numbers(String json, String path) throws UnsupportedEncodingException {
        return JsonPath.<List<Number>>read(json, path).stream().map(Number::doubleValue).toList();
    }
}

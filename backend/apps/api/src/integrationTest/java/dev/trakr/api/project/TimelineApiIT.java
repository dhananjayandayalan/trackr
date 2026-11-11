package dev.trakr.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** GET /projects/{key}/timeline over the dev seed: MAR's three epics with dates (R__dev_seed_reports.sql). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TimelineApiIT {

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;

    @Autowired
    TimelineApiIT(MockMvcTester mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @Test
    void epicsInDateOrderWithChildrenAndProgress() throws Exception {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        String body = ada.get("/api/v1/projects/MAR/timeline").getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$.epics[*].key")).startsWith("MAR-1", "MAR-2", "MAR-3");
        assertThat(JsonPath.<String>read(body, "$.epics[0].startDate")).isEqualTo("2026-10-19");
        assertThat(JsonPath.<String>read(body, "$.epics[0].dueDate")).isEqualTo("2026-11-27");
        Map<String, Object> counts = jdbc.sql("""
                        SELECT count(*) AS children, count(*) FILTER (WHERE status_category = 'done') AS done
                        FROM issue WHERE parent_id = ? AND deleted_at IS NULL""")
                .params(TestBrowser.seedId("MAR:issue:1")).query().singleRow();
        int children = ((Number) counts.get("children")).intValue();
        int done = ((Number) counts.get("done")).intValue();
        assertThat(JsonPath.<Integer>read(body, "$.epics[0].childCount")).isEqualTo(children);
        assertThat(JsonPath.<List<?>>read(body, "$.epics[0].children")).hasSize(children);
        assertThat(JsonPath.<Integer>read(body, "$.epics[0].progressPct")).isEqualTo(done * 100 / children);
    }

    @Test
    void aWindowKeepsOverlappingAndUndatedEpics() throws Exception {
        TestBrowser ada = TestBrowser.loggedIn(mvc, "ada@trakr.dev");

        // MAR-1 ends 27 Nov, MAR-2 runs to 18 Dec, MAR-3 starts 16 Nov: only MAR-1 is over by December
        String body = ada.get("/api/v1/projects/MAR/timeline?from=2026-12-01&to=2026-12-31")
                .getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$.epics[*].key"))
                .contains("MAR-2", "MAR-3").doesNotContain("MAR-1");
    }
}

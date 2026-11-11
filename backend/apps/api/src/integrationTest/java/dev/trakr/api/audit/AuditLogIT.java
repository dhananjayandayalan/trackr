package dev.trakr.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.project.ProjectService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** 09-security §9: membership changes leave a before → after row, written in the change's own transaction. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AuditLogIT {

    private static final UUID ADA = TestBrowser.seedId("user:ada");
    private static final UUID GRACE = TestBrowser.seedId("user:grace");

    private final ProjectService projects;
    private final JdbcClient jdbc;

    @Autowired
    AuditLogIT(ProjectService projects, JdbcClient jdbc) {
        this.projects = projects;
        this.jdbc = jdbc;
    }

    @Test
    void aRoleChangeIsAuditedWithBeforeAndAfter() {
        projects.changeRole(ADA, "MAR", GRACE, ProjectRole.VIEWER);

        Map<String, Object> row = jdbc.sql("""
                        SELECT actor_id, action, target, before ->> 'role' AS before_role, after ->> 'role' AS after_role
                        FROM audit_log WHERE target = ? ORDER BY id DESC LIMIT 1""")
                .params("project:" + TestBrowser.seedId("project:MAR"))
                .query().singleRow();
        assertThat(row).containsEntry("actor_id", ADA)
                .containsEntry("action", "project.member.changed")
                .containsEntry("before_role", "member")
                .containsEntry("after_role", "viewer");
    }

    @Test
    void theApiMayAppendButNeverRewriteHistory() {
        assertThat(jdbc.sql("SELECT has_table_privilege('audit_log', 'INSERT')").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT has_table_privilege('audit_log', 'UPDATE')").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT has_table_privilege('audit_log', 'DELETE')").query(Boolean.class).single()).isFalse();
    }
}

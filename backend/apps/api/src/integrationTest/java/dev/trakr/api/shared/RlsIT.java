package dev.trakr.api.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Phase 18's row-level security (V014): the database itself keeps workspaces apart, under whatever SQL the
 * api runs. Hand-written SQL on purpose — no service, no access check — so only the policies stand between
 * the query and another workspace's rows.
 *
 * <p>Ada (seed) works in the trakr-demo workspace; a freshly signed-up user gets a workspace of their own with
 * nothing in it, and is not a member of Ada's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RlsIT {

    private static final UUID ADA = TestBrowser.seedId("user:ada");
    private static final UUID MAR = TestBrowser.seedId("project:MAR");

    private final DataSource dataSource;
    private final MockMvcTester mvc;

    @Autowired
    RlsIT(DataSource dataSource, MockMvcTester mvc) {
        this.dataSource = dataSource;
        this.mvc = mvc;
    }

    @Test
    void appConnectsAsItsOwnRoleNotTheOwner() throws SQLException {
        assertThat(scalar("SELECT current_user", String.class)).isEqualTo("trakr_api");
        assertThat(scalar("SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
    }

    @Test
    void aUserSeesTheIssuesOfTheirWorkspacesOnly() throws SQLException {
        UUID stranger = signUpStranger();

        long adaSees = asUser(ADA, () -> count("SELECT count(*) FROM issue WHERE project_id = '" + MAR + "'"));
        long strangerSees = asUser(stranger, () -> count("SELECT count(*) FROM issue WHERE project_id = '" + MAR + "'"));

        assertThat(adaSees).isPositive();
        // even naming the project's id outright returns nothing: MAR's workspace isn't one of theirs
        assertThat(strangerSees).isZero();
        assertThat(asUser(stranger, () -> count("SELECT count(*) FROM project WHERE id = '" + MAR + "'"))).isZero();
    }

    @Test
    void aUserCannotWriteIntoAnotherWorkspace() {
        UUID stranger = signUpStranger();

        // the policy's USING also checks the rows an UPDATE would write: this one matches nothing to update
        long updated = asUser(stranger,
                () -> count("WITH u AS (UPDATE issue SET summary = 'pwned' WHERE project_id = '" + MAR
                        + "' RETURNING 1) SELECT count(*) FROM u"));
        assertThat(updated).isZero();
        // and an INSERT that names Ada's workspace is refused outright
        assertThatThrownBy(() -> asUser(stranger, () -> count("""
                WITH i AS (INSERT INTO saved_filter (id, workspace_id, owner_id, name, tql)
                           SELECT gen_random_uuid(), id, '%s', 'x', 'project = MAR'
                           FROM workspace WHERE slug = 'trakr-demo' RETURNING 1)
                SELECT count(*) FROM i""".formatted(stranger))))
                .hasRootCauseMessage("ERROR: new row violates row-level security policy for table \"saved_filter\"");
    }

    @Test
    void anAnonymousRequestSeesNoTenantRowsAndBackgroundWorkSeesAll() throws SQLException {
        // background (no HTTP request, nobody signed in): Kafka consumers, schedulers — every tenant
        long all = count("SELECT count(*) FROM issue");
        assertThat(all).isPositive();
        // through the HTTP stack without a session, the api refuses before any SQL; the policy is the net below
        assertThat(mvc.get().uri("/api/v1/projects/MAR/board")).hasStatus(401);
    }

    // ---- helpers

    private UUID signUpStranger() {
        String email = TestBrowser.uniqueEmail();
        TestBrowser.signedUp(mvc, "Mallory", email);
        try {
            return UUID.fromString(scalar("SELECT id::text FROM app_user WHERE email = '" + email + "'", String.class));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    interface Sql<T> {
        T run() throws SQLException;
    }

    /** Runs as if signed in as the user: TenantDataSource reads the security context on every borrow. */
    private <T> T asUser(UUID userId, Sql<T> sql) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, java.util.List.of()));
        try {
            return sql.run();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long count(String sql) throws SQLException {
        return scalar(sql, Long.class);
    }

    private <T> T scalar(String sql, Class<T> type) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getObject(1, type);
        }
    }
}

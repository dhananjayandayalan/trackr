package dev.trakr.api.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tells Postgres who is asking, on every connection the api borrows from the pool — the input of the
 * row-level-security policies in V014 (02-data-model §7, 09-security §6).
 *
 * <p><b>Who</b> ({@link Tenant#current()}):
 * <ul>
 *   <li>a signed-in user → {@code app.user_id = <their id>}: tenant tables show only rows of their workspaces;</li>
 *   <li>work outside any HTTP request (Kafka consumers, schedulers, startup) → {@code app.rls_bypass = on};</li>
 *   <li>an HTTP request with nobody signed in → neither: tenant tables look empty (fail closed).</li>
 * </ul>
 *
 * <p><b>When</b>: the phase plan offered two transaction hooks — a {@code JpaTransactionManager} subclass whose
 * {@code doBegin} runs {@code set_config(..., true)}, or a {@code TransactionSynchronization} doing the same via
 * {@code DataSourceUtils}. Both only cover code inside a Spring transaction. Here jOOQ also reads outside one
 * (each query borrows a connection in auto-commit), and those reads would silently see nothing. So this sits one
 * level lower: a {@link DelegatingDataSource} around Hikari that sets both values on <em>every</em> borrow — a
 * transaction borrows once at its start, so inside one this is exactly the transaction hook; outside, each
 * statement gets the right tenant too. The values are session-level ({@code false} below) because a borrow in
 * auto-commit has no transaction to be local to — and that is still safe with a pool: every borrow overwrites
 * both, so whatever the previous borrower set can't leak into the next. The price is one round trip per borrow.
 *
 * <p>Flyway never comes through here: it connects as the owner with its own DataSource (spring.flyway.user).
 */
public class TenantDataSource extends DelegatingDataSource {

    private static final String SET = "SELECT set_config('app.user_id', ?, false), set_config('app.rls_bypass', ?, false)";

    public TenantDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    private static Connection prepare(Connection connection) throws SQLException {
        Tenant tenant = Tenant.current();
        try (PreparedStatement statement = connection.prepareStatement(SET)) {
            statement.setString(1, tenant.userId() == null ? "" : tenant.userId().toString());
            statement.setString(2, tenant.bypass() ? "on" : "off");
            statement.execute();
        } catch (SQLException | RuntimeException e) {
            // never hand out a connection whose tenant is unknown: it would carry the previous borrower's
            connection.close();
            throw e;
        }
        return connection;
    }

    // set while a thread serves an HTTP request (RequestMarker). Not RequestContextHolder: Spring's test framework
    // puts a mock request on every test thread, so that can't tell "a request" from "a test calling a service"
    private static final ThreadLocal<Boolean> IN_REQUEST = new ThreadLocal<>();

    /** Marks the thread as serving a request, for as long as the request runs (TenantDataSourceConfig registers it first). */
    static final class RequestMarker extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            IN_REQUEST.set(Boolean.TRUE);
            try {
                chain.doFilter(request, response);
            } finally {
                IN_REQUEST.remove();
            }
        }
    }

    /** Whose rows the current thread may see. */
    public record Tenant(@Nullable UUID userId, boolean bypass) {

        static final Tenant NOBODY = new Tenant(null, false);
        static final Tenant SYSTEM = new Tenant(null, true);

        public static Tenant current() {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
                // TrakrPrincipal's name is the user id, for password and OAuth logins alike (shared can't import
                // identity, so it reads the name, not the type)
                UUID userId = parse(auth.getName());
                if (userId != null) {
                    return new Tenant(userId, false);
                }
            }
            // a request thread without a user: login, sign-up, a rejected call — none may read tenant rows
            return IN_REQUEST.get() != null ? NOBODY : SYSTEM;
        }

        private static @Nullable UUID parse(String name) {
            try {
                return UUID.fromString(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}

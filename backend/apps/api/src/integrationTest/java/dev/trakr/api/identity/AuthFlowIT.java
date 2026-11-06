package dev.trakr.api.identity;

import static dev.trakr.api.identity.IdentityServiceIT.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.trakr.api.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

// the whole filter chain (Spring Session, CSRF, security) with Postgres and Redis in containers.
// RANDOM_PORT, not the default mock environment: Spring Boot applies server.servlet.session.cookie.*
// (TRAKR_SESSION, HttpOnly, SameSite) only when an embedded server runs, as it does in production.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthFlowIT {

    private static final String PASSWORD = "correct-horse-7";

    @Autowired
    MockMvcTester mvc;

    // JavaMailSender is Spring's, not ours, so a test double is fine; it records instead of sending
    @MockitoBean
    JavaMailSender mailSender;

    @Test
    void signUpThenMeThenLogoutThenMeIsUnauthorized() {
        Browser browser = new Browser();
        String email = uniqueEmail();

        // anonymous: 401 as problem+json, and the first response already hands out the CSRF cookie
        assertThat(browser.get("/api/v1/me"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");
        assertThat(browser.cookie("XSRF-TOKEN")).isNotNull();

        assertThat(browser.post("/api/v1/auth/signup", signUp("Ada Lovelace", email)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.workspace.role").isEqualTo("owner");
        Cookie session = browser.cookie("TRAKR_SESSION");
        assertThat(session).isNotNull();
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.getAttribute("SameSite")).isEqualTo("Lax");

        assertThat(browser.get("/api/v1/me"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.email").isEqualTo(email);

        assertThat(browser.post("/api/v1/auth/logout", "")).hasStatus(HttpStatus.NO_CONTENT);

        // replaying the old cookie proves the session is gone from Redis, not just from the browser
        assertThat(new Browser(session).get("/api/v1/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mutationsWithoutCsrfHeaderAre403CsrfInvalid() {
        Browser browser = new Browser();
        browser.get("/api/v1/me");

        assertThat(browser.postWithoutCsrfHeader("/api/v1/auth/signup", signUp("Ada", uniqueEmail())))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("CSRF_INVALID");
    }

    @Test
    void invalidBodyIs400WithFieldErrors() {
        Browser browser = new Browser();
        browser.get("/api/v1/me");

        assertThat(browser.post("/api/v1/auth/signup", signUp("Ada", "not-an-email")))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> code.assertThat().isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.errors[0].field", field -> field.assertThat().isEqualTo("email"));
    }

    @Test
    void duplicateEmailIs409() {
        String email = uniqueEmail();
        Browser browser = new Browser();
        browser.get("/api/v1/me");
        browser.post("/api/v1/auth/signup", signUp("Ada", email));

        Browser other = new Browser();
        other.get("/api/v1/me");
        assertThat(other.post("/api/v1/auth/signup", signUp("Ada", email.toUpperCase())))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void loginChecksThePasswordAndRememberMeMakesThePersistentCookie() {
        String email = signedUpUser();
        Browser browser = new Browser();
        browser.get("/api/v1/me");

        assertThat(browser.post("/api/v1/auth/login", login(email, "wrong-password-1", false)))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");

        assertThat(browser.post("/api/v1/auth/login", login(email, PASSWORD, false))).hasStatusOk();
        // a browser-session cookie: no Max-Age, gone when the browser closes
        assertThat(browser.cookie("TRAKR_SESSION").getMaxAge()).isEqualTo(-1);

        Browser remembered = new Browser();
        remembered.get("/api/v1/me");
        assertThat(remembered.post("/api/v1/auth/login", login(email.toUpperCase(), PASSWORD, true))).hasStatusOk();
        assertThat(remembered.cookie("TRAKR_SESSION").getMaxAge()).isPositive();
        assertThat(remembered.get("/api/v1/me")).hasStatusOk();
    }

    @Test
    void tenFailedLoginsLockTheEmailWith429() {
        String email = signedUpUser();
        Browser browser = new Browser();
        browser.get("/api/v1/me");

        for (int i = 0; i < 10; i++) {
            assertThat(browser.post("/api/v1/auth/login", login(email, "wrong-password-1", false)))
                    .hasStatus(HttpStatus.UNAUTHORIZED);
        }

        // locked even with the right password
        assertThat(browser.post("/api/v1/auth/login", login(email, PASSWORD, false)))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .bodyJson().extractingPath("$.code").isEqualTo("RATE_LIMITED");
    }

    @Test
    void passwordResetEmailsAOneTimeLinkAndEndsOldSessions() {
        String email = uniqueEmail();
        Browser oldDevice = new Browser();
        oldDevice.get("/api/v1/me");
        oldDevice.post("/api/v1/auth/signup", signUp("Ada", email));

        Browser browser = new Browser();
        browser.get("/api/v1/me");
        assertThat(browser.post("/api/v1/auth/password/forgot", """
                {"email": "%s"}""".formatted(email))).hasStatus(HttpStatus.ACCEPTED);
        String token = tokenFromEmailTo(email);

        assertThat(browser.post("/api/v1/auth/password/reset", reset(token, "short")))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(browser.post("/api/v1/auth/password/reset", reset(token, "new-horse-8!")))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(oldDevice.get("/api/v1/me")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(browser.post("/api/v1/auth/password/reset", reset(token, "third-horse-9!")))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(browser.post("/api/v1/auth/login", login(email, "new-horse-8!", false))).hasStatusOk();
    }

    @Test
    void forgotPasswordForUnknownEmailIsAlso202ButSendsNothing() {
        Browser browser = new Browser();
        browser.get("/api/v1/me");

        assertThat(browser.post("/api/v1/auth/password/forgot", """
                {"email": "%s"}""".formatted(uniqueEmail()))).hasStatus(HttpStatus.ACCEPTED);
        verifyNoInteractions(mailSender);
    }

    private String signedUpUser() {
        String email = uniqueEmail();
        Browser browser = new Browser();
        browser.get("/api/v1/me");
        assertThat(browser.post("/api/v1/auth/signup", signUp("Ada", email))).hasStatus(HttpStatus.CREATED);
        return email;
    }

    private String tokenFromEmailTo(String email) {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly(email);
        Matcher link = Pattern.compile("/reset-password\\?token=([A-Za-z0-9_-]+)").matcher(sent.getValue().getText());
        assertThat(link.find()).isTrue();
        return link.group(1);
    }

    private static String signUp(String name, String email) {
        return """
                {"name": "%s", "email": "%s", "password": "%s"}""".formatted(name, email, PASSWORD);
    }

    private static String login(String email, String password, boolean remember) {
        return """
                {"email": "%s", "password": "%s", "remember": %s}""".formatted(email, password, remember);
    }

    private static String reset(String token, String password) {
        return """
                {"token": "%s", "password": "%s"}""".formatted(token, password);
    }

    /** Keeps cookies between requests like a browser, and echoes XSRF-TOKEN in X-XSRF-TOKEN like the SPA will. */
    private final class Browser {

        private final Map<String, Cookie> cookies = new HashMap<>();

        Browser(Cookie... initial) {
            for (Cookie cookie : initial) {
                cookies.put(cookie.getName(), cookie);
            }
        }

        MvcTestResult get(String uri) {
            return send(mvc.get().uri(uri));
        }

        MvcTestResult post(String uri, String json) {
            MockMvcRequestBuilder request = mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json);
            Cookie xsrf = cookies.get("XSRF-TOKEN");
            if (xsrf != null) {
                request.header("X-XSRF-TOKEN", xsrf.getValue());
            }
            return send(request);
        }

        MvcTestResult postWithoutCsrfHeader(String uri, String json) {
            return send(mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json));
        }

        @Nullable
        Cookie cookie(String name) {
            return cookies.get(name);
        }

        private MvcTestResult send(MockMvcRequestBuilder request) {
            if (!cookies.isEmpty()) {
                request.cookie(cookies.values().toArray(Cookie[]::new));
            }
            MvcTestResult result = request.exchange();
            for (Cookie cookie : result.getResponse().getCookies()) {
                if (cookie.getMaxAge() == 0) {
                    cookies.remove(cookie.getName());
                } else {
                    cookies.put(cookie.getName(), cookie);
                }
            }
            return result;
        }
    }
}

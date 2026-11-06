package dev.trakr.api.identity;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

// the whole filter chain (Spring Session, CSRF, security) with Postgres and Redis in containers.
// RANDOM_PORT, not the default mock environment: Spring Boot applies server.servlet.session.cookie.*
// (TRAKR_SESSION, HttpOnly, SameSite) only when an embedded server runs, as it does in production.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthFlowIT {

    private static final String PASSWORD = TestBrowser.PASSWORD;

    @Autowired
    MockMvcTester mvc;

    // JavaMailSender is Spring's, not ours, so a test double is fine; it records instead of sending
    @MockitoBean
    JavaMailSender mailSender;

    @Test
    void signUpThenMeThenLogoutThenMeIsUnauthorized() {
        TestBrowser browser = new TestBrowser(mvc);
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
        assertThat(new TestBrowser(mvc, session).get("/api/v1/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mutationsWithoutCsrfHeaderAre403CsrfInvalid() {
        TestBrowser browser = new TestBrowser(mvc);
        browser.get("/api/v1/me");

        assertThat(browser.postWithoutCsrfHeader("/api/v1/auth/signup", signUp("Ada", uniqueEmail())))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("CSRF_INVALID");
    }

    @Test
    void invalidBodyIs400WithFieldErrors() {
        TestBrowser browser = new TestBrowser(mvc);
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
        TestBrowser browser = new TestBrowser(mvc);
        browser.get("/api/v1/me");
        browser.post("/api/v1/auth/signup", signUp("Ada", email));

        TestBrowser other = new TestBrowser(mvc);
        other.get("/api/v1/me");
        assertThat(other.post("/api/v1/auth/signup", signUp("Ada", email.toUpperCase())))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void loginChecksThePasswordAndRememberMeMakesThePersistentCookie() {
        String email = signedUpUser();
        TestBrowser browser = new TestBrowser(mvc);
        browser.get("/api/v1/me");

        assertThat(browser.post("/api/v1/auth/login", login(email, "wrong-password-1", false)))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");

        assertThat(browser.post("/api/v1/auth/login", login(email, PASSWORD, false))).hasStatusOk();
        // a browser-session cookie: no Max-Age, gone when the browser closes
        assertThat(browser.cookie("TRAKR_SESSION").getMaxAge()).isEqualTo(-1);

        TestBrowser remembered = new TestBrowser(mvc);
        remembered.get("/api/v1/me");
        assertThat(remembered.post("/api/v1/auth/login", login(email.toUpperCase(), PASSWORD, true))).hasStatusOk();
        assertThat(remembered.cookie("TRAKR_SESSION").getMaxAge()).isPositive();
        assertThat(remembered.get("/api/v1/me")).hasStatusOk();
    }

    @Test
    void tenFailedLoginsLockTheEmailWith429() {
        String email = signedUpUser();
        TestBrowser browser = new TestBrowser(mvc);
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
        TestBrowser oldDevice = new TestBrowser(mvc);
        oldDevice.get("/api/v1/me");
        oldDevice.post("/api/v1/auth/signup", signUp("Ada", email));

        TestBrowser browser = new TestBrowser(mvc);
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
        TestBrowser browser = new TestBrowser(mvc);
        browser.get("/api/v1/me");

        assertThat(browser.post("/api/v1/auth/password/forgot", """
                {"email": "%s"}""".formatted(uniqueEmail()))).hasStatus(HttpStatus.ACCEPTED);
        verifyNoInteractions(mailSender);
    }

    private String signedUpUser() {
        String email = uniqueEmail();
        TestBrowser browser = new TestBrowser(mvc);
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
}

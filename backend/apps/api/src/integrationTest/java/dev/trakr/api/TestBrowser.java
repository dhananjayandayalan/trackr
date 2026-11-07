package dev.trakr.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.kernel.Ids;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Plays the browser in integration tests: keeps cookies between requests, and echoes the
 * XSRF-TOKEN cookie in the X-XSRF-TOKEN header like the SPA will.
 */
public final class TestBrowser {

    public static final String PASSWORD = "correct-horse-7";

    private final MockMvcTester mvc;
    private final Map<String, Cookie> cookies = new HashMap<>();

    public TestBrowser(MockMvcTester mvc, Cookie... initial) {
        this.mvc = mvc;
        for (Cookie cookie : initial) {
            cookies.put(cookie.getName(), cookie);
        }
    }

    /** An id from the dev seed: Postgres's md5(name)::uuid, i.e. the MD5 digest's 32 hex digits as a UUID. */
    public static UUID seedId(String name) {
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(name.getBytes(StandardCharsets.UTF_8)));
            return UUID.fromString(hex.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String uniqueEmail() {
        return "user-" + Ids.newId() + "@example.com";
    }

    /** A fresh account (with its own workspace), already signed in. */
    public static TestBrowser signedUp(MockMvcTester mvc, String name, String email) {
        TestBrowser browser = new TestBrowser(mvc);
        browser.get("/api/v1/me");
        assertThat(browser.post("/api/v1/auth/signup", """
                {"name": "%s", "email": "%s", "password": "%s"}""".formatted(name, email, PASSWORD)))
                .hasStatus(HttpStatus.CREATED);
        return browser;
    }

    /** An existing account, signed in with a password (the dev seed's ada@trakr.dev and grace@trakr.dev use PASSWORD). */
    public static TestBrowser loggedIn(MockMvcTester mvc, String email) {
        TestBrowser browser = new TestBrowser(mvc);
        browser.get("/api/v1/me");
        assertThat(browser.post("/api/v1/auth/login", """
                {"email": "%s", "password": "%s", "remember": false}""".formatted(email, PASSWORD)))
                .hasStatusOk();
        return browser;
    }

    public MvcTestResult get(String uri) {
        return send(mvc.get().uri(uri));
    }

    public MvcTestResult post(String uri, String json) {
        return send(mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public MvcTestResult put(String uri) {
        return send(mvc.put().uri(uri));
    }

    public MvcTestResult delete(String uri) {
        return send(mvc.delete().uri(uri));
    }

    public MvcTestResult postWithoutCsrfHeader(String uri, String json) {
        return exchange(mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Any request built from {@link #mvc()}, with this browser's cookies and CSRF header. */
    public MvcTestResult send(MockMvcRequestBuilder request) {
        Cookie xsrf = cookies.get("XSRF-TOKEN");
        if (xsrf != null) {
            request.header("X-XSRF-TOKEN", xsrf.getValue());
        }
        return exchange(request);
    }

    public MockMvcTester mvc() {
        return mvc;
    }

    public @Nullable Cookie cookie(String name) {
        return cookies.get(name);
    }

    private MvcTestResult exchange(MockMvcRequestBuilder request) {
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

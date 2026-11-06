package dev.trakr.api.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// a slice: only Spring MVC, the advice and this test's controller — no database, no security filters
@WebMvcTest(ProblemDetailsTest.FailingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ProblemDetailsTest.FailingController.class)
class ProblemDetailsTest {

    @RestController
    @RequestMapping("/test")
    static class FailingController {

        record Body(@NotBlank String name) {
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new TrakrException.NotFound("No project MAR.");
        }

        @GetMapping("/forbidden")
        void forbidden() {
            throw new TrakrException.Forbidden("This needs the admin role in MAR.");
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new TrakrException.Conflict("LAST_ADMIN", "A project needs at least one admin.");
        }

        @GetMapping("/version-mismatch")
        void versionMismatch() {
            throw new TrakrException.VersionMismatch(Map.of("key", "MAR", "version", 4));
        }

        @GetMapping("/optimistic-lock")
        void optimisticLock() {
            throw new ObjectOptimisticLockingFailureException(Object.class, "id");
        }

        @GetMapping("/unique-key")
        void uniqueKey() {
            throw new DataIntegrityViolationException(
                    "ERROR: duplicate key value violates unique constraint \"uq_project_workspace_key\"");
        }

        @GetMapping("/unique-other")
        void uniqueOther() {
            throw new DataIntegrityViolationException("ERROR: violates foreign key constraint \"fk_x\"");
        }

        @PostMapping("/validated")
        void validated(@Valid @RequestBody Body body) {
        }
    }

    @Autowired
    MockMvcTester mvc;

    @ParameterizedTest(name = "{0} → {1} {2}")
    @CsvSource({
            "/test/not-found,       404, NOT_FOUND",
            "/test/forbidden,       403, FORBIDDEN",
            "/test/conflict,        409, LAST_ADMIN",
            "/test/version-mismatch, 412, VERSION_MISMATCH",
            "/test/optimistic-lock, 412, VERSION_MISMATCH",
            "/test/unique-key,      409, PROJECT_KEY_TAKEN",
            "/test/unique-other,    409, CONFLICT",
            // Spring MVC's own errors get a code too
            "/test/no-such-path,    404, NOT_FOUND"})
    void everyErrorIsAProblemWithACode(String path, int status, String code) {
        assertIsProblem(mvc.get().uri(path).exchange(), status, code);
    }

    @Test
    void wrongMethodIs405WithACode() {
        assertIsProblem(mvc.post().uri("/test/not-found").exchange(), 405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void invalidBodyIs400WithFieldErrors() {
        MvcTestResult result = mvc.post().uri("/test/validated").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"\"}").exchange();

        assertIsProblem(result, 400, "VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");
    }

    @Test
    void versionMismatchCarriesTheCurrentResource() {
        assertThat(mvc.get().uri("/test/version-mismatch"))
                .bodyJson().extractingPath("$.current.version").isEqualTo(4);
    }

    private static void assertIsProblem(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson()
                .hasPathSatisfying("$.type", type -> type.assertThat().asString()
                        .isEqualTo("https://trakr.dev/problems/" + code.toLowerCase().replace('_', '-')))
                .hasPathSatisfying("$.title", title -> title.assertThat().isNotNull())
                .hasPathSatisfying("$.status", value -> value.assertThat().isEqualTo(status))
                .hasPathSatisfying("$.code", value -> value.assertThat().isEqualTo(code));
    }
}

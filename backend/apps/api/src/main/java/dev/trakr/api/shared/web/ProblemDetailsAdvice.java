package dev.trakr.api.shared.web;

import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.api.shared.web.TrakrException.VersionMismatch;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as application/problem+json with {@code type}, {@code title},
 * {@code status}, {@code detail} and a {@code code} (03-api §2). The security filters hand their
 * 401/403 here as well, see SecurityConfig.
 */
// BLOCK 1: extend the Spring base — Spring MVC's own errors (404, 405, bad JSON, missing header) become problems too
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    // unique constraints whose violation has a meaning the client can act on
    private static final Map<String, String> CONSTRAINT_CODES = Map.of(
            "uq_project_workspace_key", "PROJECT_KEY_TAKEN",
            "uq_app_user_email", "EMAIL_TAKEN");

    // BLOCK 2: our exceptions
    @ExceptionHandler(TrakrException.class)
    ResponseEntity<ProblemDetail> handleTrakrException(TrakrException ex) {
        ProblemDetail problem = problem(ex.status(), ex.code(), ex.getMessage());
        if (!ex.errors().isEmpty()) {
            problem.setProperty("errors", ex.errors());
        }
        ex.properties().forEach(problem::setProperty);
        // BLOCK 4 (our half): a stale If-Match carries the current resource
        if (ex instanceof VersionMismatch mismatch && mismatch.current() != null) {
            problem.setProperty("current", mismatch.current());
        }
        return ResponseEntity.status(ex.status()).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleUnauthenticated(AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue.");
    }

    // more specific than AccessDeniedException (CsrfException extends it), so it wins for CSRF failures
    @ExceptionHandler(CsrfException.class)
    ResponseEntity<ProblemDetail> handleCsrf(CsrfException ex) {
        return respond(HttpStatus.FORBIDDEN, "CSRF_INVALID", "Missing or invalid X-XSRF-TOKEN header.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleForbidden(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do this.");
    }

    // BLOCK 3: validation
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldMessage> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldMessage(error.getField(), String.valueOf(error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request has invalid fields.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    // BLOCK 4: optimistic locking — two writers loaded the same version, the second UPDATE matched no row
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return handleTrakrException(new VersionMismatch(null));
    }

    // BLOCK 5: unique violations — the database is the only place a race can't slip past
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage());
        return CONSTRAINT_CODES.entrySet().stream()
                .filter(constraint -> message.contains(constraint.getKey()))
                .findFirst()
                .map(constraint -> respond(HttpStatus.CONFLICT, constraint.getValue(), "This value is already taken."))
                .orElseGet(() -> respond(HttpStatus.CONFLICT, "CONFLICT", "The change conflicts with existing data."));
    }

    // Spring's own errors (404 no route, 405, 415, missing header…) get their ProblemDetail from the base class
    // (body is still null here), but no code: let it build the body, then derive one from the status
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            String code = status == null ? "ERROR" : status.name();
            problem.setType(typeOf(code));
            problem.setProperty("code", code);
        }
        return response;
    }

    private static ResponseEntity<ProblemDetail> respond(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).body(problem(status, code, detail));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(typeOf(code));
        problem.setProperty("code", code);
        return problem;
    }

    // VALIDATION_FAILED → https://trakr.dev/problems/validation-failed
    private static URI typeOf(String code) {
        return URI.create("https://trakr.dev/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}

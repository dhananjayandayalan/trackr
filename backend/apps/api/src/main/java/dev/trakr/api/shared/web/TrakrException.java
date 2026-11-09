package dev.trakr.api.shared.web;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * An error that ProblemDetailsAdvice turns into an RFC 9457 problem with a stable {@code code}
 * (03-api §2). Services throw these; controllers never build error responses themselves.
 */
public class TrakrException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One entry of the problem's {@code errors[]}. */
    public record FieldMessage(String field, String message) {
    }

    private final HttpStatus status;
    private final String code;
    private final transient List<FieldMessage> errors;

    public TrakrException(HttpStatus status, String code, String detail) {
        this(status, code, detail, List.of());
    }

    public TrakrException(HttpStatus status, String code, String detail, List<FieldMessage> errors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldMessage> errors() {
        return errors;
    }

    /** Extra members of the problem body (TQL errors add {@code position} and {@code expected}); none by default. */
    public Map<String, Object> properties() {
        return Map.of();
    }

    /** 404 — also when the caller may not see the thing, so its existence doesn't leak. */
    public static class NotFound extends TrakrException {

        private static final long serialVersionUID = 1L;

        public NotFound(String detail) {
            super(HttpStatus.NOT_FOUND, "NOT_FOUND", detail);
        }
    }

    /** 403 — the caller can see the thing but not do this to it. */
    public static class Forbidden extends TrakrException {

        private static final long serialVersionUID = 1L;

        public Forbidden(String detail) {
            super(HttpStatus.FORBIDDEN, "FORBIDDEN", detail);
        }
    }

    /** 409 with a specific code, e.g. PROJECT_KEY_TAKEN, LAST_ADMIN. */
    public static class Conflict extends TrakrException {

        private static final long serialVersionUID = 1L;

        public Conflict(String code, String detail) {
            super(HttpStatus.CONFLICT, code, detail);
        }
    }

    /** 412 — the client edited an old version; {@code current} goes into the problem so it can merge and retry. */
    public static class VersionMismatch extends TrakrException {

        private static final long serialVersionUID = 1L;

        private final transient @Nullable Object current;

        public VersionMismatch(@Nullable Object current) {
            super(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH",
                    "Someone else changed this in the meantime. Reload and try again.");
            this.current = current;
        }

        public @Nullable Object current() {
            return current;
        }
    }
}

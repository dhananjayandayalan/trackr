package dev.trakr.api.shared.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/** An error that ApiExceptionHandler turns into an RFC 9457 problem with a stable {@code code}. */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One entry of the problem's {@code errors[]}. */
    public record FieldMessage(String field, String message) {
    }

    private final HttpStatus status;
    private final String code;
    private final transient List<FieldMessage> errors;

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, List.of());
    }

    public ApiException(HttpStatus status, String code, String detail, List<FieldMessage> errors) {
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
}

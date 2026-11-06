package dev.trakr.api.identity;

import dev.trakr.api.shared.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;

public class WeakPasswordException extends ApiException {

    private static final long serialVersionUID = 1L;

    public WeakPasswordException(List<String> violations) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The password is too weak.",
                violations.stream().map(message -> new FieldMessage("password", message)).toList());
    }
}

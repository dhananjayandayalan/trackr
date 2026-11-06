package dev.trakr.api.identity.internal;

import dev.trakr.api.shared.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;

class InvalidResetTokenException extends ApiException {

    private static final long serialVersionUID = 1L;

    InvalidResetTokenException() {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "This reset link is invalid, used or expired.",
                List.of(new FieldMessage("token", "is invalid, used or expired")));
    }
}

package dev.trakr.api.identity;

import dev.trakr.api.shared.web.TrakrException;
import org.springframework.http.HttpStatus;

public class EmailTakenException extends TrakrException {

    private static final long serialVersionUID = 1L;

    public EmailTakenException() {
        super(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account with this email already exists.");
    }
}

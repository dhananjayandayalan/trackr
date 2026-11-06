package dev.trakr.api.identity;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * A Google or GitHub login we can't attach to an account. It is an OAuth2AuthenticationException so
 * Spring Security sends the browser to the OAuth failure handler instead of answering 500.
 */
public class AccountLinkRefusedException extends OAuth2AuthenticationException {

    private static final long serialVersionUID = 1L;

    public AccountLinkRefusedException(String reason) {
        super(new OAuth2Error("account_link_refused"), reason);
    }
}

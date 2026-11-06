package dev.trakr.api.identity.internal;

import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.identity.UserView;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/** Google sign-in (OpenID Connect): Google's user becomes our user. */
@Service
class OAuthUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final IdentityService identityService;

    OAuthUserService(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        // 1. Spring validates the id_token and loads the user info
        OidcUser google = delegate.loadUser(request);
        // 2. + 3. sub is Google's stable id for the person; the email may change, sub never does
        UserView user = identityService.linkOrCreateFromOAuth(
                request.getClientRegistration().getRegistrationId(),
                google.getSubject(),
                google.getEmail(),
                Boolean.TRUE.equals(google.getEmailVerified()),
                google.getFullName());
        // 4. our principal, carrying our user id
        return TrakrPrincipal.of(user, google.getIdToken());
    }
}

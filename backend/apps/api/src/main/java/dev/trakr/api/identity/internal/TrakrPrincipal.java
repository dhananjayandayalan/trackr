package dev.trakr.api.identity.internal;

import dev.trakr.api.identity.UserView;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The signed-in user, however they signed in. It is a UserDetails (password login) and an OidcUser
 * (Google; GitHub uses its OAuth2User half), so controllers take one type:
 * {@code @AuthenticationPrincipal TrakrPrincipal}. Spring Session stores it in Redis inside the
 * SecurityContext, so it stays small and serializable (UserDetails extends Serializable).
 */
public record TrakrPrincipal(UUID id, String email, @Nullable String password, @Nullable OidcIdToken idToken)
        implements UserDetails, OidcUser {

    /** For DaoAuthenticationProvider, which compares the password hash. */
    static TrakrPrincipal withPasswordHash(AppUser user) {
        return new TrakrPrincipal(user.id(), user.email(), user.passwordHash(), null);
    }

    public static TrakrPrincipal of(UserView user) {
        return new TrakrPrincipal(user.id(), user.email(), null, null);
    }

    static TrakrPrincipal of(UserView user, OidcIdToken idToken) {
        return new TrakrPrincipal(user.id(), user.email(), null, idToken);
    }

    /** What goes into the session: the hash has done its job and must not sit in Redis. */
    public TrakrPrincipal withoutPassword() {
        return new TrakrPrincipal(id, email, null, idToken);
    }

    // the name Spring Security reports and Spring Session indexes sessions by: the user id, for every login type
    @Override
    public String getUsername() {
        return id.toString();
    }

    @Override
    public String getName() {
        return id.toString();
    }

    @Override
    public @Nullable String getPassword() {
        return password;
    }

    // no roles yet: Spring Security only decides signed-in or not; project roles are checked in services
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return getClaims();
    }

    @Override
    public Map<String, Object> getClaims() {
        return idToken == null ? Map.of() : idToken.getClaims();
    }

    @Override
    public @Nullable OidcUserInfo getUserInfo() {
        return null;
    }

    @Override
    public @Nullable OidcIdToken getIdToken() {
        return idToken;
    }
}

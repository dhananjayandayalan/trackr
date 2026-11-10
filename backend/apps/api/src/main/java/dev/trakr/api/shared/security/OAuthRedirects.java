package dev.trakr.api.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * Where the browser lands after an OAuth2 login. The SPA starts the flow with
 * {@code /oauth2/authorization/google?next=/projects}; we keep {@code next} in the session while the
 * browser visits the provider, then send it back there — but only to a path on our own origin.
 */
final class OAuthRedirects {

    private static final String NEXT = OAuthRedirects.class.getName() + ".NEXT";

    private OAuthRedirects() {
    }

    static OAuth2AuthorizationRequestResolver rememberingNext(ClientRegistrationRepository registrations) {
        var delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return remember(request, delegate.resolve(request));
            }

            @Override
            public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
                return remember(request, delegate.resolve(request, registrationId));
            }
        };
    }

    static void onSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        HttpSession session = request.getSession(false);
        Object next = session == null ? null : session.getAttribute(NEXT);
        if (session != null) {
            session.removeAttribute(NEXT);
            // the user id as a plain attribute, for the collab app (like AuthController.startSession)
            session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, authentication.getName());
        }
        response.sendRedirect(next instanceof String path && isSameOriginPath(path) ? path : "/");
    }

    static void onFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.sendRedirect("/login?error=oauth");
    }

    private static @Nullable OAuth2AuthorizationRequest remember(HttpServletRequest request,
            @Nullable OAuth2AuthorizationRequest authorization) {
        String next = request.getParameter("next");
        if (authorization != null && next != null) {
            request.getSession().setAttribute(NEXT, next);
        }
        return authorization;
    }

    // "/projects" is fine; "//evil.com" and "/\evil.com" are other hosts to a browser
    static boolean isSameOriginPath(String path) {
        return path.startsWith("/") && !path.startsWith("//") && !path.contains("\\");
    }
}

package dev.trakr.collab.security;

import dev.trakr.collab.config.CollabProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Runs on the HTTP request that asks to become a WebSocket ({@code GET /collab/issues/MAR-131},
 * {@code Upgrade: websocket}), before the upgrade. Returning false answers that request with the status we set, and
 * no socket is ever opened (06-collab §5).
 *
 * <p>The Origin check happened just before this, in Spring's OriginHandshakeInterceptor (WebSocketConfig's
 * allowed origins): a page on another site can make the browser send our cookie with its handshake — SameSite=Lax
 * doesn't stop a cross-site WebSocket handshake — but it can't fake the Origin header.
 */
@Component
public class SessionAuthHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SessionAuthHandshakeInterceptor.class);
    private static final Pattern PATH = Pattern.compile("^/collab/issues/([A-Z]{2,6}-[1-9][0-9]{0,17})$");

    private final SessionReader sessions;
    private final IssueAccess access;
    private final String cookieName;

    SessionAuthHandshakeInterceptor(SessionReader sessions, IssueAccess access, CollabProperties properties) {
        this.sessions = sessions;
        this.access = access;
        this.cookieName = properties.session().cookieName();
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Map<String, Object> attributes) {
        // BLOCK 1: which issue — the last path segment, in the shape of an issue key
        Matcher path = PATH.matcher(request.getURI().getPath());
        if (!path.matches()) {
            return refuse(response, HttpStatus.NOT_FOUND);
        }
        String issueKey = path.group(1);

        // BLOCK 2: who — the api's session cookie, looked up in the shared Redis
        Optional<UUID> userId = cookie(request).flatMap(sessions::userId);
        if (userId.isEmpty()) {
            return refuse(response, HttpStatus.UNAUTHORIZED);
        }

        // BLOCK 3: may they — 404 also when they can't see it, so nobody learns which private issues exist
        Optional<IssueAccess.Grant> grant = access.find(userId.get(), issueKey);
        if (grant.isEmpty()) {
            return refuse(response, HttpStatus.NOT_FOUND);
        }

        // BLOCK 4: remember the answer for the socket's whole life (WebSocketSession.getAttributes())
        attributes.put(Connection.ATTRIBUTE, new Connection(userId.get(), grant.get()));
        log.debug("handshake ok issue={} user={} role={}", issueKey, userId.get(), grant.get().role());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            @Nullable Exception exception) {
        // nothing to clean up
    }

    private Optional<String> cookie(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servlet)) {
            return Optional.empty();
        }
        HttpServletRequest http = servlet.getServletRequest();
        Cookie[] cookies = http.getCookies();
        return cookies == null ? Optional.empty() : Arrays.stream(cookies)
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private static boolean refuse(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        return false;
    }
}

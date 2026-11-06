package dev.trakr.api.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Safe retries for POSTs that create things (03-api §1). The client sends a fresh UUID as
 * {@code Idempotency-Key}; if the network drops and it sends the same request again, it gets the
 * first response back instead of a second project. Runs inside the security chain (see
 * SecurityConfig) because the key is scoped to the signed-in user.
 */
public final class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    private static final Duration TTL = Duration.ofHours(24);
    private static final String PENDING = "pending";

    record StoredResponse(int status, @Nullable String contentType, @Nullable String location, @Nullable String etag,
            String body) {
    }

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final HandlerExceptionResolver errors;

    public IdempotencyFilter(StringRedisTemplate redis, JsonMapper json, HandlerExceptionResolver errors) {
        this.redis = redis;
        this.json = json;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || request.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(HEADER);
        if (!isUuid(key)) {
            fail(request, response, new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Idempotency-Key must be a UUID."));
            return;
        }

        // scoped by user: another user who happens to send the same UUID must not receive my response.
        // The principal's name is the user id (TrakrPrincipal), so shared needs no identity types.
        String redisKey = "idem:" + authentication.getName() + ":" + key;
        // SET … NX: only one request can claim the key, even when two retries arrive at the same moment
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, PENDING, TTL))) {
            replayOrRefuse(redisKey, request, response);
            return;
        }

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, captured);
        } catch (IOException | ServletException | RuntimeException e) {
            redis.delete(redisKey);
            throw e;
        }
        if (captured.getStatus() >= 500) {
            // a server failure is not an answer: let the retry run the request again
            redis.delete(redisKey);
        } else {
            StoredResponse stored = new StoredResponse(captured.getStatus(), captured.getContentType(),
                    captured.getHeader(HttpHeaders.LOCATION), captured.getHeader(HttpHeaders.ETAG),
                    new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8));
            redis.opsForValue().set(redisKey, json.writeValueAsString(stored), TTL);
        }
        captured.copyBodyToResponse();
    }

    private void replayOrRefuse(String redisKey, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String value = redis.opsForValue().get(redisKey);
        if (value == null || PENDING.equals(value)) {
            fail(request, response, new TrakrException.Conflict("REQUEST_IN_PROGRESS",
                    "A request with this Idempotency-Key is still running."));
            return;
        }
        StoredResponse stored = json.readValue(value, StoredResponse.class);
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        if (stored.location() != null) {
            response.setHeader(HttpHeaders.LOCATION, stored.location());
        }
        if (stored.etag() != null) {
            response.setHeader(HttpHeaders.ETAG, stored.etag());
        }
        response.setHeader("Idempotent-Replayed", "true");
        response.getOutputStream().write(stored.body().getBytes(StandardCharsets.UTF_8));
    }

    // filters run before Spring MVC, so errors go through the same advice as controller errors
    private void fail(HttpServletRequest request, HttpServletResponse response, TrakrException ex) {
        errors.resolveException(request, response, null, ex);
    }

    private static boolean isUuid(@Nullable String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

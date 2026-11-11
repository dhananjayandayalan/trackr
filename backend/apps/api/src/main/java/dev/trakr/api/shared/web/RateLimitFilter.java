package dev.trakr.api.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Rate limits (09-security §8): a Redis token bucket per caller and route group.
 * <ul>
 *   <li>{@code auth} — sign-in, sign-up, password reset: per client IP, 5 a minute. Nobody is signed in yet,
 *       and this is what slows down password guessing across many accounts (LoginAttempts locks one account).</li>
 *   <li>{@code search} — every /api/v1/search call: per user, 10/s with bursts of 20 (each is an ES query).</li>
 *   <li>{@code write} — any other POST/PUT/PATCH/DELETE: per user, 20/s with bursts of 40.</li>
 * </ul>
 * Reads are not limited: they are cheap, cached by the browser, and a board opening fires a dozen at once.
 *
 * <p>Over the limit → 429 problem+json with {@code Retry-After} (seconds). Redis down → the request goes
 * through: losing rate limiting for a minute is better than losing the whole api.
 *
 * <p>Runs in the security chain after AuthorizationFilter (SecurityConfig): the user is known, and requests
 * that will be refused anyway (401) don't spend tokens. Behind Caddy, {@code getRemoteAddr()} is the browser's
 * address because server.forward-headers-strategy=native lets Tomcat read X-Forwarded-For from the proxy.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    // the script's SHA is computed once; Redis runs it with EVALSHA and only receives the source on a cache miss
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET =
            RedisScript.of(new ClassPathResource("redis/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties limits;
    private final HandlerExceptionResolver errors;

    public RateLimitFilter(StringRedisTemplate redis, RateLimitProperties limits, HandlerExceptionResolver errors) {
        this.redis = redis;
        this.limits = limits;
        this.errors = errors;
    }

    /** Which bucket a request draws from; null = not limited. */
    record Group(String name, RateLimitProperties.Bucket bucket) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limits.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Group group = groupOf(request);
        String caller = group == null ? null : callerOf(request, group);
        if (group == null || caller == null) {
            chain.doFilter(request, response);
            return;
        }
        Decision decision = take("rl:" + caller + ":" + group.name(), group.bucket());
        if (decision.allowed()) {
            response.setHeader("RateLimit-Remaining", String.valueOf(decision.remaining()));
            chain.doFilter(request, response);
            return;
        }
        // whole seconds, rounded up: "Retry-After: 0" would invite an immediate retry that fails again
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (decision.waitMillis() + 999) / 1000)));
        errors.resolveException(request, response, null, new TrakrException(HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED", "Too many requests. Try again in a moment."));
    }

    // BLOCK 1: route groups
    @Nullable Group groupOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/v1/auth/") && "POST".equals(method)) {
            return new Group("auth", limits.auth());
        }
        if (path.startsWith("/api/v1/search")) {
            return new Group("search", limits.search());
        }
        boolean write = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        return write ? new Group("write", limits.write()) : null;
    }

    // BLOCK 2: the caller — the client IP for auth, the user id (the principal's name) for the rest
    private static @Nullable String callerOf(HttpServletRequest request, Group group) {
        if (group.name().equals("auth")) {
            return request.getRemoteAddr();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth instanceof AnonymousAuthenticationToken ? null : auth.getName();
    }

    // BLOCK 3: one EVALSHA = one atomic check-and-spend
    record Decision(boolean allowed, long waitMillis, long remaining) {
    }

    Decision take(String key, RateLimitProperties.Bucket bucket) {
        try {
            List<?> result = redis.execute(TOKEN_BUCKET, List.of(key), String.valueOf(bucket.perSecond()),
                    String.valueOf(bucket.burst()), "1");
            return new Decision(((Number) result.get(0)).longValue() == 1, ((Number) result.get(1)).longValue(),
                    ((Number) result.get(2)).longValue());
        } catch (RuntimeException e) {
            log.warn("Rate limiting is off for this request, Redis failed: {}", e.toString());
            return new Decision(true, 0, -1);
        }
    }
}

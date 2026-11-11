package dev.trakr.api.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.shared.web.RateLimitFilter;
import dev.trakr.api.shared.web.RateLimitProperties;
import dev.trakr.api.shared.web.RateLimitProperties.Bucket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The token bucket against a real Redis (09-security §8). The filter is built here with tiny limits instead of
 * changing the app's: the rest of the suite signs in hundreds of times from 127.0.0.1, so integration tests run
 * with trakr.rate-limit.enabled=false (build.gradle.kts), and a context of its own would cost a container set.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RateLimitIT {

    private final RateLimitFilter filter;

    @Autowired
    RateLimitIT(StringRedisTemplate redis, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.filter = new RateLimitFilter(redis, new RateLimitProperties(true,
                new Bucket(1, Duration.ofSeconds(1), 2),
                new Bucket(1, Duration.ofSeconds(1), 2),
                // 3 a minute, all at once at most
                new Bucket(3, Duration.ofMinutes(1), 3)), errors);
    }

    @Test
    void theFourthSignInAMinuteFromOneAddressIsRefusedWithRetryAfter() throws Exception {
        String ip = "10.0.0." + (int) (Math.random() * 250);

        for (int i = 0; i < 3; i++) {
            assertThat(signIn(ip).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse refused = signIn(ip);

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getContentType()).isEqualTo("application/problem+json");
        assertThat(refused.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
        // a token comes back every 20 s: the client is told when, in whole seconds
        assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 20);
        // another address has its own bucket
        assertThat(signIn("10.1.0." + (int) (Math.random() * 250)).getStatus()).isEqualTo(200);
    }

    @Test
    void readsAreNotLimitedButWritesAreAndTheBucketRefills() throws Exception {
        String user = UUID.randomUUID().toString();
        for (int i = 0; i < 10; i++) {
            assertThat(call("GET", "/api/v1/projects", user).getStatus()).isEqualTo(200);
        }
        assertThat(call("POST", "/api/v1/issues", user).getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/v1/issues", user).getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/v1/issues", user).getStatus()).isEqualTo(429);

        // 1 token per second: a second later one write fits again
        Thread.sleep(1_100);
        assertThat(call("PATCH", "/api/v1/issues/MAR-1", user).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse signIn(String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletResponse call(String method, String uri, String userId) throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated(userId, null, java.util.List.of()));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            return response;
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}

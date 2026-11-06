package dev.trakr.api.identity.internal;

import java.time.Duration;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Lockout: 10 failed logins for one email within 15 minutes → 429 until the window ends. The counter
 * lives in Redis so every api instance sees the same count, and Redis deletes it when the TTL runs out.
 */
@Component
public class LoginAttempts {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    LoginAttempts(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isLocked(String email) {
        String failures = redis.opsForValue().get(key(email));
        return failures != null && Integer.parseInt(failures) >= MAX_FAILURES;
    }

    public void recordFailure(String email) {
        // INCR creates the key at 1; the window starts at the first failure
        Long failures = redis.opsForValue().increment(key(email));
        if (failures != null && failures == 1) {
            redis.expire(key(email), WINDOW);
        }
    }

    public void reset(String email) {
        redis.delete(key(email));
    }

    private static String key(String email) {
        return "login:fail:" + email.toLowerCase(Locale.ROOT);
    }
}

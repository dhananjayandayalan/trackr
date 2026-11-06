package dev.trakr.api.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AppUserTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @CsvSource({
            "Ada Lovelace, AL",
            "ada, A",
            "'  grace   brewster murray hopper ', GH",
            "élodie durand, ÉD"})
    void initialsAreFirstAndLastWordUppercased(String displayName, String initials) {
        assertThat(AppUser.initialsOf(displayName)).isEqualTo(initials);
    }

    @Test
    void registerDerivesInitialsAndAvatarColorAndStampsCreation() {
        AppUser user = AppUser.register("ada@x.com", "Ada Lovelace", "hash", clock);

        assertThat(user.toView().initials()).isEqualTo("AL");
        assertThat(user.toView().avatarColor()).isIn("pink", "cyan", "lime", "yellow", "orange", "violet");
        assertThat(user.emailVerifiedAt()).isNull();
    }

    @Test
    void markEmailVerifiedKeepsTheFirstTime() {
        AppUser user = AppUser.register("ada@x.com", "Ada", null, clock);

        user.markEmailVerified(clock);
        user.markEmailVerified(Clock.offset(clock, Duration.ofDays(1)));

        assertThat(user.emailVerifiedAt()).isEqualTo(clock.instant());
    }
}

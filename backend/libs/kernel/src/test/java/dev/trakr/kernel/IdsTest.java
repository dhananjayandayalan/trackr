package dev.trakr.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    // BLOCK 1: the bits say "UUIDv7, RFC 9562 variant"
    @Test
    void newIdIsVersion7WithRfcVariant() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    // BLOCK 2: strictly increasing, compared as text (UUID.compareTo is signed)
    @Test
    void idsAreStrictlyIncreasing() {
        String previous = Ids.newId().toString();

        for (int i = 0; i < 10_000; i++) {
            String next = Ids.newId().toString();
            assertThat(next).isGreaterThan(previous);
            previous = next;
        }
    }

    // BLOCK 3: the embedded timestamp is "now"
    @Test
    void timestampOfReturnsCreationTime() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID id = Ids.newId();
        Instant after = Instant.now();

        // slack above: newId() may borrow a few milliseconds ahead under load
        assertThat(Ids.timestampOf(id)).isBetween(before, after.plusMillis(100));
    }
}

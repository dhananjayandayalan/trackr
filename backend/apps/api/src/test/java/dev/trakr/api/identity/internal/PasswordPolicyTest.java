package dev.trakr.api.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    // table-driven: one row per case; an empty "violation" column means "accepted"
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', textBlock = """
            correct-horse-7     |
            Tr0ub4dor&3xyz      |
            short1!             | must be at least 10 characters
            no-digits-here!     | must contain a number
            onlyLetters123      | must contain a symbol
            Password123!        | is too common
            QWERTY123!          | is too common
            """)
    void checksLengthDigitSymbolAndCommonPasswords(String password, String violation) {
        if (violation == null) {
            assertThat(policy.violations(password)).isEmpty();
        } else {
            assertThat(policy.violations(password)).containsExactly(violation);
        }
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThat(policy.violations("abc")).hasSize(3);
    }
}

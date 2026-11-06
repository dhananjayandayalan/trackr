package dev.trakr.api.identity.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 09-security §1: at least 10 characters, one number, one symbol, and not a well-known password. */
@Component
public class PasswordPolicy {

    static final int MIN_LENGTH = 10;

    // passwords that pass the rules above yet top every leaked-password list; compared lower-cased
    private static final Set<String> COMMON = Set.of(
            "password1!", "password12!", "password123!", "p@ssword123", "p@ssw0rd123", "passw0rd!!",
            "qwerty123!", "qwertyuiop1!", "1q2w3e4r5t!", "1qaz2wsx3edc!", "123456789a!", "abc123456!",
            "welcome123!", "letmein123!", "iloveyou123!", "admin12345!", "changeme123!", "trustno1!!",
            "football123!", "baseball123!", "sunshine123!", "monkey12345!", "dragon12345!", "trakr12345!");

    /** What is wrong with the password; empty when it is acceptable. */
    public List<String> violations(String password) {
        List<String> violations = new ArrayList<>();
        if (password.length() < MIN_LENGTH) {
            violations.add("must be at least " + MIN_LENGTH + " characters");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            violations.add("must contain a number");
        }
        if (password.chars().allMatch(Character::isLetterOrDigit)) {
            violations.add("must contain a symbol");
        }
        if (COMMON.contains(password.toLowerCase(Locale.ROOT))) {
            violations.add("is too common");
        }
        return violations;
    }
}

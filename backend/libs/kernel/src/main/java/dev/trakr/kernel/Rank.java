package dev.trakr.kernel;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Fractional indexing: sortable strings that always have room between them, so moving an issue
 * rewrites only that issue's rank (00-product §4.6).
 *
 * <p>A rank is a base-62 fraction written without the leading "0.": {@code "V"} is 31/62, about
 * 0.5, and {@code "VV"} is a bit more than that. Comparing two ranks as strings compares the
 * fractions, because the digits {@code 0-9A-Za-z} are in ASCII order. Postgres must compare them
 * the same way, byte by byte, which is what {@code COLLATE "C"} on the column does.
 *
 * <p>The frontend's {@code rank.ts} (phase 14) must give the same answers; the shared cases are in
 * {@code docs/spec/fixtures/rank-vectors.json}, written by {@code RankTest}.
 */
public final class Rank {

    // BLOCK 1: alphabet and helpers
    static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = DIGITS.length();
    private static final char ZERO = DIGITS.charAt(0);
    private static final char LAST = DIGITS.charAt(BASE - 1);

    private Rank() {
    }

    private static int digitValue(char digit) {
        int value = DIGITS.indexOf(digit);
        if (value < 0) {
            throw new IllegalArgumentException("not a rank digit: '" + digit + "'");
        }
        return value;
    }

    private static char digit(int value) {
        return DIGITS.charAt(value);
    }

    // a trailing '0' adds nothing to the fraction ("V0" == "V"), and "…0" has no room directly below it
    private static void requireValid(String rank) {
        if (rank.isEmpty()) {
            throw new IllegalArgumentException("a rank cannot be empty");
        }
        for (int i = 0; i < rank.length(); i++) {
            digitValue(rank.charAt(i));
        }
        if (rank.charAt(rank.length() - 1) == ZERO) {
            throw new IllegalArgumentException("a rank cannot end in '0': " + rank);
        }
    }

    // BLOCK 2: midpoint(a, b)
    /**
     * A string strictly between {@code a} and {@code b}. {@code a} may be "" (= 0), {@code b} may be
     * null (= 1). Neither may end in '0'; the result never does.
     */
    static String midpoint(String a, @Nullable String b) {
        if (b != null) {
            // the shared prefix stays; "" counts as padded with zeros, so midpoint("", "05") keeps the "0"
            int n = 0;
            while (n < b.length() && (n < a.length() ? a.charAt(n) : ZERO) == b.charAt(n)) {
                n++;
            }
            if (n > 0) {
                return b.substring(0, n) + midpoint(a.substring(Math.min(n, a.length())), b.substring(n));
            }
        }
        // the first digits differ
        int digitA = a.isEmpty() ? 0 : digitValue(a.charAt(0));
        int digitB = b == null ? BASE : digitValue(b.charAt(0));
        if (digitB - digitA > 1) {
            // room for a digit in between: take the middle one ("1" and "9" → "5")
            return String.valueOf(digit((digitA + digitB + 1) / 2));
        }
        // neighbouring digits, like "4…" and "5…"
        if (b != null && b.length() > 1) {
            // "5" alone sorts before "5x" and after anything starting with "4"
            return b.substring(0, 1);
        }
        // b is "5" or +∞: keep a's first digit and find room after the rest of a ("49" and "5" → "4" + "9V")
        return digit(digitA) + midpoint(a.isEmpty() ? "" : a.substring(1), null);
    }

    // BLOCK 3: between(before, after)
    /**
     * The rank for a new position: {@code between(null, null)} for the first item of an empty list,
     * {@code between(last, null)} to append, {@code between(null, first)} to prepend, both to insert.
     *
     * <p>Appending and prepending step by one digit instead of halving the gap: the bottom of the
     * backlog grows by one character per ~30 issues, not one per ~6.
     */
    public static String between(@Nullable String before, @Nullable String after) {
        if (before != null) {
            requireValid(before);
        }
        if (after != null) {
            requireValid(after);
        }
        if (before == null && after == null) {
            // the middle digit, room on both sides; fractional-indexing libraries use "a0", but that ends in '0'
            return midpoint("", null);
        }
        if (after == null) {
            return after(before);
        }
        if (before == null) {
            return before(after);
        }
        if (before.compareTo(after) >= 0) {
            throw new IllegalArgumentException("before must sort below after: " + before + " >= " + after);
        }
        return midpoint(before, after);
    }

    // the first digit that can go up by one, with everything after it dropped: "V" → "W", "zz3" → "zz4", "z" → "zV"
    private static String after(String rank) {
        for (int i = 0; i < rank.length(); i++) {
            if (rank.charAt(i) != LAST) {
                return rank.substring(0, i) + digit(digitValue(rank.charAt(i)) + 1);
            }
        }
        return rank + digit(BASE / 2);
    }

    // the first digit that can go down by one without becoming a trailing '0': "V" → "U", "1V" → "1U", "1" → "0V"
    private static String before(String rank) {
        for (int i = 0; i < rank.length(); i++) {
            if (digitValue(rank.charAt(i)) > 1) {
                return rank.substring(0, i) + digit(digitValue(rank.charAt(i)) - 1);
            }
        }
        // only 0s and 1s, ending in '1': replace that last '1' with "0V"
        return midpoint("", rank);
    }

    // BLOCK 4: nBetween(before, after, n)
    /**
     * {@code n} ranks between {@code before} and {@code after}, in order, spread by bisection so none
     * of them is much longer than the others (sprint completion moves many issues at once).
     */
    public static List<String> nBetween(@Nullable String before, @Nullable String after, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must not be negative: " + n);
        }
        List<String> ranks = new ArrayList<>(n);
        fill(before, after, n, ranks);
        return List.copyOf(ranks);
    }

    // the middle one first, then the halves on each side of it
    private static void fill(@Nullable String before, @Nullable String after, int n, List<String> out) {
        if (n == 0) {
            return;
        }
        String middle = between(before, after);
        int left = (n - 1) / 2;
        fill(before, middle, left, out);
        out.add(middle);
        fill(middle, after, n - 1 - left, out);
    }
}

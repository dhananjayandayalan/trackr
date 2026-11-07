package dev.trakr.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RankTest {

    // BLOCK 1: the basic cases
    @Test
    void firstRankIsTheMiddleDigit() {
        assertThat(Rank.between(null, null)).isEqualTo("V");
    }

    @Test
    void appendAndPrependStepByOneDigit() {
        assertThat(Rank.between("V", null)).isEqualTo("W");
        assertThat(Rank.between("z", null)).isEqualTo("zV");
        assertThat(Rank.between(null, "V")).isEqualTo("U");
        assertThat(Rank.between(null, "1")).isEqualTo("0V");
    }

    @Test
    void insertsBetweenNeighbours() {
        assertThat(Rank.between("U", "W")).isEqualTo("V");
        // no digit fits between U and V: go one level deeper
        assertThat(Rank.between("U", "V")).isEqualTo("UV");
        assertThat(Rank.between("UV", "V")).isEqualTo("Ul");
    }

    @Test
    void rejectsBadInput() {
        assertThatIllegalArgumentException().isThrownBy(() -> Rank.between("W", "V"));
        assertThatIllegalArgumentException().isThrownBy(() -> Rank.between("V", "V"));
        assertThatIllegalArgumentException().isThrownBy(() -> Rank.between("V0", null));
        assertThatIllegalArgumentException().isThrownBy(() -> Rank.between("", null));
        assertThatIllegalArgumentException().isThrownBy(() -> Rank.between("a-b", null));
    }

    // BLOCK 2: property-style — random inserts keep the list sorted and unique
    @Test
    void tenThousandRandomInsertsStaySortedAndUnique() {
        Random random = new Random(42);
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            int index = random.nextInt(list.size() + 1);
            String before = index == 0 ? null : list.get(index - 1);
            String after = index == list.size() ? null : list.get(index);
            String rank = Rank.between(before, after);
            assertThat(rank).doesNotEndWith("0");
            list.add(index, rank);
        }

        assertThat(list).isSorted().doesNotHaveDuplicates();
    }

    // BLOCK 3: the worst cases for length — always at the front, always at the end, always in the same gap
    @Test
    void thousandInsertsAtTheFrontStayShort() {
        String first = Rank.between(null, null);
        for (int i = 0; i < 1_000; i++) {
            String next = Rank.between(null, first);
            assertThat(next).isLessThan(first);
            first = next;
        }

        assertThat(first.length()).isLessThan(200);
    }

    @Test
    void thousandInsertsAtTheEndStayShort() {
        String last = Rank.between(null, null);
        for (int i = 0; i < 1_000; i++) {
            String next = Rank.between(last, null);
            assertThat(next).isGreaterThan(last);
            last = next;
        }

        assertThat(last.length()).isLessThan(200);
    }

    @Test
    void thousandInsertsIntoTheSameGapStayUnder200() {
        // always right after the first item: each insert halves the same gap, ~6 bits per character
        String low = "U";
        String high = "V";
        for (int i = 0; i < 1_000; i++) {
            high = Rank.between(low, high);
        }

        assertThat(high.length()).isLessThan(200);
    }

    // BLOCK 4: nBetween
    @Test
    void nBetweenSpreadsEvenly() {
        List<String> ranks = Rank.nBetween("U", "W", 100);

        assertThat(ranks).hasSize(100).isSorted().doesNotHaveDuplicates();
        assertThat(ranks.getFirst()).isGreaterThan("U");
        assertThat(ranks.getLast()).isLessThan("W");
        // bisection: about log62(100) extra characters, not one per key
        assertThat(ranks).allSatisfy(rank -> assertThat(rank.length()).isLessThanOrEqualTo(4));
    }

    @Test
    void nBetweenWorksWithOpenEnds() {
        assertThat(Rank.nBetween(null, null, 5)).hasSize(5).isSorted().doesNotHaveDuplicates();
        assertThat(Rank.nBetween("z", null, 3)).hasSize(3).isSorted().allSatisfy(r -> assertThat(r).isGreaterThan("z"));
        assertThat(Rank.nBetween(null, null, 0)).isEmpty();
    }

    // BLOCK 5: shared vectors for the TypeScript port
    record Case(@Nullable String before, @Nullable String after, int n) {
    }

    private static final List<Case> VECTOR_CASES = List.of(
            new Case(null, null, 1), new Case("V", null, 1), new Case("z", null, 1), new Case("zz", null, 1),
            new Case("zV", null, 1), new Case(null, "V", 1), new Case(null, "1", 1), new Case(null, "01", 1),
            new Case(null, "1V", 1), new Case("U", "W", 1), new Case("U", "V", 1), new Case("UV", "V", 1),
            new Case("1", "2", 1), new Case("49", "5", 1), new Case("0V", "1", 1), new Case("a", "a1", 1),
            new Case("a1", "a2", 1), new Case("Zz", "a", 1), new Case("V", "VV", 1), new Case("00V", "01", 1),
            new Case(null, null, 5), new Case("U", "W", 10), new Case("V", null, 4), new Case(null, "V", 4),
            new Case("a", "b", 7));

    /**
     * Compares {@code docs/spec/fixtures/rank-vectors.json} with what Rank computes. To rewrite the
     * file after a deliberate change: {@code ./gradlew :libs:kernel:test -PupdateRankVectors=true}.
     */
    @Test
    void matchesTheSharedVectors() throws IOException {
        String expected = vectorsJson();
        Path file = Path.of(System.getProperty("rank.vectors", "../../../docs/spec/fixtures/rank-vectors.json"));
        if (Boolean.getBoolean("rank.vectors.update")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, expected, StandardCharsets.UTF_8);
        }

        assertThat(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n")).isEqualTo(expected);
    }

    // hand-written JSON: kernel has no JSON library, and the values are only strings, nulls and numbers
    private static String vectorsJson() {
        StringBuilder json = new StringBuilder("{\n  \"digits\": \"" + Rank.DIGITS + "\",\n  \"cases\": [\n");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < VECTOR_CASES.size(); i++) {
            Case c = VECTOR_CASES.get(i);
            assertThat(seen.add(c.toString())).as("duplicate case %s", c).isTrue();
            List<String> result = c.n() == 1 ? List.of(Rank.between(c.before(), c.after()))
                    : Rank.nBetween(c.before(), c.after(), c.n());
            json.append("    {\"before\": ").append(quoted(c.before()))
                    .append(", \"after\": ").append(quoted(c.after()))
                    .append(", \"n\": ").append(c.n())
                    .append(", \"result\": [")
                    .append(String.join(", ", result.stream().map(RankTest::quoted).toList()))
                    .append("]}")
                    .append(i < VECTOR_CASES.size() - 1 ? ",\n" : "\n");
        }
        return json.append("  ]\n}\n").toString();
    }

    private static String quoted(@Nullable String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}

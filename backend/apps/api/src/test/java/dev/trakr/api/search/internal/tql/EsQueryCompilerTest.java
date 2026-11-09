package dev.trakr.api.search.internal.tql;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import dev.trakr.api.search.internal.tql.EsQueryCompiler.Compiled;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

/**
 * TQL in, Elasticsearch JSON out, compared with the files in src/test/resources/tql/. The JSON is what
 * the typed client would send, serialized by the client's own mapper — so the files are also readable
 * documentation of what each TQL construct becomes.
 */
class EsQueryCompilerTest {

    static final UUID ME = id(1);
    static final UUID ADA = id(2);
    static final UUID GRACE = id(3);
    static final UUID SPRINT_1 = id(11);
    static final UUID SPRINT_2 = id(12);
    static final UUID SPRINT_3 = id(13);
    static final UUID EPIC = id(100);

    // Monday 9 November 2026, 09:00 UTC: this week ends on Sunday the 15th
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneOffset.UTC);
    private static final Jackson3JsonpMapper MAPPER = new Jackson3JsonpMapper();

    /** A directory that knows a few names, like the dev seed. */
    static final ValueResolver.Directory DIRECTORY = new ValueResolver.Directory() {
        @Override
        public List<UUID> usersNamed(String nameOrEmail) {
            return switch (nameOrEmail.toLowerCase(Locale.ROOT)) {
                case "ada lovelace", "ada@trakr.dev" -> List.of(ADA);
                case "grace hopper" -> List.of(GRACE);
                default -> List.of();
            };
        }

        @Override
        public List<UUID> membersOf(String projectKey) {
            return projectKey.equals("MAR") ? List.of(ADA, GRACE) : List.of();
        }

        @Override
        public List<UUID> sprintsNamed(String name) {
            return name.equalsIgnoreCase("MAR Sprint 2") ? List.of(SPRINT_2) : List.of();
        }

        @Override
        public List<UUID> sprintsInStates(Set<String> states) {
            return states.contains("closed") ? List.of(SPRINT_1) : List.of(SPRINT_2, SPRINT_3);
        }

        @Override
        public List<UUID> issuesWithKey(String key) {
            return key.equals("MAR-100") ? List.of(EPIC) : List.of();
        }

        @Override
        public List<String> statusNames(String name) {
            return Map.of("done", List.of("Done"), "in review", List.of("In review"))
                    .getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }
    };

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
            // the five examples of 05-search §3
            "spec-1 | project = MAR AND status != Done ORDER BY priority DESC, updated DESC",
            "spec-2 | assignee = currentUser() AND sprint in openSprints()",
            "spec-3 | text ~ \"checkout crash\" AND type in (Bug, Task) AND created >= -7d",
            "spec-4 | labels in (frontend, ux) AND storyPoints > 3 AND epic = MAR-100",
            "spec-5 | priority >= High AND (flagged = true OR due <= endOfWeek())",
            // nested ANDs and ORs flatten into one bool each
            "flatten | type = bug OR type = task OR type = story",
            "empty | assignee IS EMPTY AND NOT due IS NOT EMPTY",
            "dates | created = 2026-11-01 OR resolved > 2026-11-01 OR updated != -1d",
            "names | assignee in (\"Ada Lovelace\", membersOf(\"MAR\")) AND sprint = \"MAR Sprint 2\" AND status = \"in review\"",
            "match-all | ORDER BY key, summary DESC",
    })
    void compilesTo(String file, String tql) throws Exception {
        JSONAssert.assertEquals(expected(file), compile(tql), JSONCompareMode.STRICT);
    }

    @Test
    void unknownNamesAreErrorsAtTheirPosition() {
        assertInvalid("assignee = Nobody", 11, "No user named 'Nobody'.");
        assertInvalid("status = Doen", 9, "No status named 'Doen'.");
        assertInvalid("type = Bugg", 7, "'Bugg' is not a type.");
        assertInvalid("stauts = Done", 0, "Unknown field 'stauts'.");
        assertInvalid("labels > 3", 0, "'labels' doesn't support '>'.");
        assertInvalid("summary = login", 0, "'summary' doesn't support '='.");
        assertInvalid("project = MAR ORDER BY labels", 23, "Can't sort by 'labels'.");
        assertInvalid("sprint = nextSprint()", 9, "'nextSprint()' can't be used with 'sprint'.");
        assertInvalid("created > Monday", 10,
                "'created' needs a date (2026-01-31), a relative date (-7d) or a date function.");
    }

    private static void assertInvalid(String tql, int position, String message) {
        assertThatThrownBy(() -> compile(tql))
                .isInstanceOfSatisfying(TqlException.class, error -> {
                    org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("TQL_INVALID_QUERY");
                    org.assertj.core.api.Assertions.assertThat(error.position()).isEqualTo(position);
                })
                .hasMessage(message);
    }

    /** {"query": …, "sort": […]}, the two parts of the request body the compiler produces. */
    static String compile(String tql) {
        Compiled compiled = new EsQueryCompiler(new ValueResolver(DIRECTORY, ME, CLOCK)).compile(TqlParser.parse(tql));
        String sort = compiled.sort().stream().map(s -> JsonpUtils.toJsonString(s, MAPPER))
                .collect(Collectors.joining(","));
        return "{\"query\":" + JsonpUtils.toJsonString(compiled.query(), MAPPER) + ",\"sort\":[" + sort + "]}";
    }

    private static String expected(String file) throws IOException {
        try (InputStream in = EsQueryCompilerTest.class.getResourceAsStream("/tql/" + file + ".json")) {
            if (in == null) {
                throw new IllegalStateException("missing src/test/resources/tql/" + file + ".json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }
}

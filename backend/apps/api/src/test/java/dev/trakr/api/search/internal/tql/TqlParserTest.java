package dev.trakr.api.search.internal.tql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Input → AST, compared through the AST's toString(), which spells out every grouping with parentheses.
 * A wrong precedence rule shows up as parentheses in the wrong place.
 */
class TqlParserTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "project = MAR | project = MAR",
            // precedence: AND binds tighter than OR, on either side
            "a = 1 OR b = 2 AND c = 3 | (a = 1 OR (b = 2 AND c = 3))",
            "a = 1 AND b = 2 OR c = 3 | ((a = 1 AND b = 2) OR c = 3)",
            // parentheses override it
            "(a = 1 OR b = 2) AND c = 3 | ((a = 1 OR b = 2) AND c = 3)",
            // chains associate to the left
            "a = 1 OR b = 2 OR c = 3 | ((a = 1 OR b = 2) OR c = 3)",
            // NOT binds tighter than AND
            "NOT a = 1 AND b = 2 | (NOT a = 1 AND b = 2)",
            "NOT (a = 1 AND b = 2) | NOT (a = 1 AND b = 2)",
            "NOT NOT flagged = true | NOT NOT flagged = true",
            // keywords in any case
            "project = MAR and status != Done | (project = MAR AND status != Done)",
            "type in (Bug, Task) | type IN (Bug, Task)",
            "status NOT IN (Done, \"In review\") | status NOT IN (Done, \"In review\")",
            "assignee IS EMPTY | assignee IS EMPTY",
            "due is not empty | due IS NOT EMPTY",
            "text ~ \"checkout crash\" | text ~ \"checkout crash\"",
            "summary ~ login | summary ~ \"login\"",
            "created >= -7d | created >= -7d",
            "due <= endOfWeek() | due <= endOfWeek()",
            "assignee in membersOf(\"MAR\") | assignee IN (membersOf(\"MAR\"))",
            "epic = mar-100 | epic = MAR-100",
            "storyPoints > 3.5 | storyPoints > 3.5",
            "resolved < 2026-11-01 | resolved < 2026-11-01",
            "summary ~ \"say \\\"hi\\\"\" | summary ~ \"say \\\"hi\\\"\"",
            // ORDER BY: ascending by default
            "project = MAR ORDER BY priority DESC, updated | project = MAR ORDER BY priority DESC, updated ASC",
            "ORDER BY rank | ORDER BY rank ASC",
    })
    void parses(String tql, String ast) {
        assertThat(TqlParser.parse(tql)).hasToString(ast);
    }

    @Test
    void anEmptyQueryMatchesEverything() {
        assertThat(TqlParser.parse("").where()).isNull();
        assertThat(TqlParser.parse("   ").orderBy()).isEmpty();
    }

    @Test
    void theExamplesFromTheSpecParse() {
        assertThat(TqlParser.parse("project = MAR AND status != Done ORDER BY priority DESC, updated DESC"))
                .hasToString("(project = MAR AND status != Done) ORDER BY priority DESC, updated DESC");
        assertThat(TqlParser.parse("assignee = currentUser() AND sprint in openSprints()"))
                .hasToString("(assignee = currentUser() AND sprint IN (openSprints()))");
        assertThat(TqlParser.parse("text ~ \"checkout crash\" AND type in (Bug, Task) AND created >= -7d"))
                .hasToString("((text ~ \"checkout crash\" AND type IN (Bug, Task)) AND created >= -7d)");
        assertThat(TqlParser.parse("labels in (frontend, ux) AND storyPoints > 3 AND epic = MAR-100"))
                .hasToString("((labels IN (frontend, ux) AND storyPoints > 3) AND epic = MAR-100)");
        assertThat(TqlParser.parse("priority >= High AND (flagged = true OR due <= endOfWeek())"))
                .hasToString("(priority >= High AND (flagged = true OR due <= endOfWeek()))");
    }

    // BLOCK: errors — position is the 0-based offset of the offending token

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // a second operator
            "status = = Done | 9",
            // a missing operator: the second name is where it goes wrong
            "project MAR | 8",
            // two clauses without AND/OR
            "project = MAR status = Done | 14",
            "type in (Bug, Task | 18",
            "(project = MAR | 14",
            "project = MAR AND | 17",
            "status = Done ORDER priority | 20",
            "a = 1 # b = 2 | 6",
            "text ~ \"unclosed | 7",
    })
    void reportsWhereTheMistakeIs(String tql, int position) {
        assertThatThrownBy(() -> TqlParser.parse(tql))
                .isInstanceOfSatisfying(TqlException.class, error -> {
                    assertThat(error.code()).isEqualTo("TQL_SYNTAX_ERROR");
                    assertThat(error.position()).isEqualTo(position);
                });
    }

    @Test
    void listsEverythingThatWouldHaveBeenValid() {
        // after a complete clause, not just "end of query": AND, OR and ORDER BY were fine too
        // ("(" as well: MAR( would start a function call)
        assertThatThrownBy(() -> TqlParser.parse("project = MAR status = Done"))
                .isInstanceOfSatisfying(TqlException.class, error -> assertThat(error.expected())
                        .containsExactlyInAnyOrder("AND", "OR", "ORDER BY", "(", "end of query"));
        assertThatThrownBy(() -> TqlParser.parse("status = = Done"))
                .isInstanceOfSatisfying(TqlException.class, error -> assertThat(error.expected())
                        .containsExactlyInAnyOrder("name", "\"text\"", "number", "relative date (-7d)",
                                "date (2026-01-31)", "issue key"));
        assertThatThrownBy(() -> TqlParser.parse("project"))
                .isInstanceOfSatisfying(TqlException.class, error -> {
                    assertThat(error.position()).isEqualTo(7);
                    assertThat(error.expected()).containsExactlyInAnyOrder("=", "!=", ">", ">=", "<", "<=", "~",
                            "IN", "NOT", "IS");
                });
    }

    @Test
    void anUnclosedQuoteSaysSo() {
        assertThatThrownBy(() -> TqlParser.parse("text ~ \"chec"))
                .hasMessage("The quote at position 7 is never closed.");
    }
}

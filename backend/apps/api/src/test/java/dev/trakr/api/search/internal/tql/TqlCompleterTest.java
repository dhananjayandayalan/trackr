package dev.trakr.api.search.internal.tql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.api.search.internal.tql.TqlCompleter.Result;
import dev.trakr.api.search.internal.tql.TqlCompleter.Suggestion;
import java.util.List;
import org.junit.jupiter.api.Test;

class TqlCompleterTest {

    // what SearchService would look up; here a few fixed values per field
    private static final TqlCompleter.Values VALUES = field -> switch (field.name()) {
        case "status" -> List.of("To do", "In progress", "In review", "Done");
        case "assignee" -> List.of("currentUser()", "Ada Lovelace", "Grace Hopper");
        case "project" -> List.of("MAR", "OPS", "BUG");
        case "sprint" -> List.of("openSprints()", "closedSprints()", "MAR Sprint 2");
        default -> List.of();
    };

    @Test
    void anEmptyQueryOffersFieldsAndNot() {
        Result result = complete("");
        assertThat(texts(result, "field")).startsWith("project", "key", "type", "priority", "status");
        assertThat(texts(result, "keyword")).containsExactly("NOT", "ORDER BY");
        assertThat(result.from()).isZero();
    }

    @Test
    void aPartialFieldNameIsReplaced() {
        Result result = complete("project = MAR AND sta");
        assertThat(texts(result, "field")).containsExactly("status", "statusCategory");
        assertThat(result.from()).isEqualTo(18);
    }

    @Test
    void afterAFieldComeItsOperators() {
        assertThat(texts(complete("status "), "operator")).containsExactly("=", "!=", "in", "not in");
        assertThat(texts(complete("assignee "), "operator"))
                .containsExactly("=", "!=", "in", "not in", "is empty", "is not empty");
        assertThat(texts(complete("text "), "operator")).containsExactly("~");
        // a half-typed operator narrows them
        assertThat(texts(complete("priority <"), "operator")).containsExactly("<", "<=");
    }

    @Test
    void afterAnOperatorComeTheFieldsValues() {
        // names with spaces arrive quoted, ready to insert
        assertThat(texts(complete("status = "), "value")).containsExactly("\"To do\"", "\"In progress\"",
                "\"In review\"", "Done");
        assertThat(texts(complete("status = in"), "value")).containsExactly("\"In progress\"", "\"In review\"");
        // a later word matches too: "hop" finds Grace Hopper
        assertThat(texts(complete("assignee = hop"), "value")).containsExactly("\"Grace Hopper\"");
        assertThat(texts(complete("project in (MAR, "), "value")).containsExactly("MAR", "OPS", "BUG");
        // after IN without a list: only what stands for a list
        assertThat(texts(complete("sprint in "), "value")).containsExactly("openSprints()", "closedSprints()");
    }

    @Test
    void insideAnOpenQuoteTheWholeQuotedValueIsReplaced() {
        Result result = complete("status = \"in r");
        assertThat(texts(result, "value")).containsExactly("\"In review\"");
        assertThat(result.from()).isEqualTo(9);
    }

    @Test
    void afterACompleteClauseComeTheConnectives() {
        assertThat(texts(complete("project = MAR "), "keyword")).containsExactly("AND", "OR", "ORDER BY");
        assertThat(texts(complete("project = MAR ORDER BY "), "field")).contains("priority", "updated", "rank")
                .doesNotContain("labels", "text");
        assertThat(texts(complete("project = MAR ORDER BY priority "), "keyword")).containsExactly("ASC", "DESC");
        assertThat(texts(complete("assignee is "), "keyword")).containsExactly("empty", "not empty");
    }

    @Test
    void aBrokenPrefixGetsNoSuggestions() {
        assertThat(complete("status = = ").expected()).isEmpty();
    }

    @Test
    void theCaretCanBeInTheMiddle() {
        // only the text before the caret counts
        assertThat(texts(TqlCompleter.complete("status = Done", 7, VALUES), "operator")).contains("=", "in");
    }

    private static Result complete(String tql) {
        return TqlCompleter.complete(tql, tql.length(), VALUES);
    }

    private static List<String> texts(Result result, String kind) {
        return result.expected().stream().filter(s -> s.kind().equals(kind)).map(Suggestion::text).toList();
    }
}

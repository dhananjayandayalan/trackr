package dev.trakr.api.search.internal.tql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.api.search.internal.tql.grammar.TqlLexer;
import java.util.List;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Token by token: the lexer rules' order and longest-match decide these, so they get their own test. */
class TqlLexerTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            // the "watch out" from the phase: one ISSUEKEY, not IDENT '-' NUMBER
            "MAR-131 | ISSUEKEY",
            "mar-131 | ISSUEKEY",
            "MAR | IDENT",
            // keywords win the tie with IDENT (same length, listed first), in any case
            "and | AND",
            "AND | AND",
            "Or | OR",
            "android | IDENT",
            "-7d | RELDATE",
            "2w | RELDATE",
            "12h | RELDATE",
            "-7 | NUMBER",
            "3.5 | NUMBER",
            "2026-11-09 | DATE",
            "storyPoints | IDENT",
            "'\"In review\"' | STRING",
            "'\"say \\\"hi\\\"\"' | STRING",
            "!= | NE",
            ">= | GE",
            "~ | TILDE",
            "# | ERROR_CHAR",
    })
    void lexesOneToken(String text, String type) {
        List<Token> tokens = TqlParser.tokens(text);
        assertThat(tokens).hasSize(1);
        assertThat(TqlLexer.VOCABULARY.getSymbolicName(tokens.getFirst().getType())).isEqualTo(type);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "project = MAR | IDENT EQ IDENT",
            "epic=MAR-100 | IDENT EQ ISSUEKEY",
            "created >= -7d | IDENT GE RELDATE",
            "status NOT IN (Done) | IDENT NOT IN LPAREN IDENT RPAREN",
            // an unclosed quote: the quote alone is an ERROR_CHAR, the rest lexes normally
            "'text ~ \"chec' | IDENT TILDE ERROR_CHAR IDENT",
    })
    void lexesASequence(String text, String types) {
        List<String> actual = TqlParser.tokens(text).stream()
                .map(token -> TqlLexer.VOCABULARY.getSymbolicName(token.getType()))
                .toList();
        assertThat(String.join(" ", actual)).isEqualTo(types);
    }
}

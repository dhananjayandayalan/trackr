package dev.trakr.api.search.internal.tql;

import dev.trakr.api.search.internal.tql.ast.TqlQuery;
import dev.trakr.api.search.internal.tql.grammar.TqlLexer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;

/**
 * String → TqlQuery, the front of the pipeline (string → tokens → parse tree → AST → Elasticsearch JSON).
 *
 * <ol>
 * <li>the lexer cuts the text into tokens ({@code project}, {@code =}, {@code MAR});
 * <li>the parser checks the token order against the grammar and builds a parse tree;
 * <li>AstBuilder turns the tree into our own records. Nothing after this class sees an ANTLR type.
 * </ol>
 *
 * <p>Error handling: ANTLR's default reaction to a mistake is to print it to the console and try to carry on
 * ("recovery"), which suits a compiler that wants to report many errors. A one-line query wants the first
 * mistake, precisely: the console listeners are removed and {@link Bail} turns the first error into an
 * exception carrying the token it happened at.
 *
 * <p>Stateless; every method builds its own lexer and parser (they are cheap and not thread-safe).
 */
public final class TqlParser {

    private TqlParser() {
    }

    /** Parses a whole query; {@link TqlException} (TQL_SYNTAX_ERROR) with position and expected[] if it isn't valid. */
    public static TqlQuery parse(String tql) {
        List<Token> tokens = tokens(tql);
        try {
            return new AstBuilder().build(parser(tokens).query());
        } catch (SyntaxError error) {
            throw describe(tql, tokens, error.offending);
        }
    }

    /** The tokens of a text, without whitespace and without the final EOF. The lexer never fails (ERROR_CHAR). */
    public static List<Token> tokens(String text) {
        TqlLexer lexer = new TqlLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        return List.copyOf(lexer.getAllTokens());
    }

    /**
     * Whether a query could still become valid by adding tokens after {@code prefix}: "{@code project =}" can,
     * "{@code project = = }" can't. Autocomplete only makes suggestions for a viable prefix.
     */
    public static boolean isViablePrefix(List<Token> prefix) {
        return failsAtOrAfter(withSentinel(prefix), prefix.size());
    }

    /**
     * Every token type that may come right after {@code prefix} — what the error message lists as "expected"
     * and what autocomplete turns into suggestions. Includes {@link Token#EOF} when the query may end there.
     *
     * <p>Why try every type instead of asking ANTLR? {@code parser.getExpectedTokens()} answers for the parser's
     * state at the moment it failed, and by then it has already left the optional parts behind: after
     * {@code project = MAR status} it only expects EOF, although AND, OR and ORDER BY would have been fine too.
     * Trying "prefix + candidate" for each of the ~30 token types finds all of them, and a query is short, so
     * that's a few dozen tiny parses, microseconds each.
     */
    public static Set<Integer> expectedAfter(List<Token> prefix) {
        Set<Integer> expected = new LinkedHashSet<>();
        for (int type = 1; type <= TqlLexer.VOCABULARY.getMaxTokenType(); type++) {
            if (type == TqlLexer.ERROR_CHAR || type == TqlLexer.WS) {
                continue;
            }
            List<Token> candidate = new ArrayList<>(prefix);
            candidate.add(new CommonToken(type, "candidate"));
            // the candidate is accepted if the parser only gets stuck AFTER it, on the sentinel
            if (failsAtOrAfter(withSentinel(candidate), prefix.size() + 1)) {
                expected.add(type);
            }
        }
        if (parses(prefix)) {
            expected.add(Token.EOF);
        }
        return expected;
    }

    /** How a token type reads in an error message or the editor: {@code =}, {@code AND}, {@code "text"}. */
    public static String display(int tokenType) {
        return switch (tokenType) {
            case Token.EOF -> "end of query";
            case TqlLexer.IDENT -> "name";
            case TqlLexer.STRING -> "\"text\"";
            case TqlLexer.NUMBER -> "number";
            case TqlLexer.RELDATE -> "relative date (-7d)";
            case TqlLexer.DATE -> "date (2026-01-31)";
            case TqlLexer.ISSUEKEY -> "issue key";
            case TqlLexer.ORDER -> "ORDER BY";
            default -> {
                // literal tokens: the vocabulary has them quoted ('and', '!='); keywords read better uppercase
                String literal = TqlLexer.VOCABULARY.getLiteralName(tokenType);
                yield literal == null ? TqlLexer.VOCABULARY.getSymbolicName(tokenType)
                        : literal.substring(1, literal.length() - 1).toUpperCase(Locale.ROOT);
            }
        };
    }

    // BLOCK: the error the user sees — where, what was found, what would have been valid
    private static TqlException describe(String tql, List<Token> tokens, Token offending) {
        int index = Math.min(offending.getTokenIndex(), tokens.size());
        List<String> expected = expectedAfter(tokens.subList(0, index)).stream().map(TqlParser::display).toList();
        if (offending.getType() == Token.EOF) {
            return TqlException.syntax("The query ends too early; expected " + String.join(", ", expected) + ".",
                    tql.length(), expected);
        }
        int position = offending.getStartIndex();
        if ("\"".equals(offending.getText())) {
            return TqlException.syntax("The quote at position " + position + " is never closed.", position, expected);
        }
        return TqlException.syntax("Unexpected '" + offending.getText() + "' at position " + position + "; expected "
                + String.join(", ", expected) + ".", position, expected);
    }

    // BLOCK: running the generated parser over a list of tokens
    static dev.trakr.api.search.internal.tql.grammar.TqlParser parser(List<Token> tokens) {
        List<Token> copies = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            // the token stream numbers the tokens it reads (setTokenIndex): give it copies, never the caller's
            copies.add(new CommonToken(token));
        }
        var parser = new dev.trakr.api.search.internal.tql.grammar.TqlParser(
                new CommonTokenStream(new ListTokenSource(copies)));
        parser.removeErrorListeners();
        parser.setErrorHandler(new Bail());
        return parser;
    }

    private static boolean parses(List<Token> tokens) {
        try {
            parser(tokens).query();
            return true;
        } catch (SyntaxError error) {
            return false;
        }
    }

    private static boolean failsAtOrAfter(List<Token> tokens, int index) {
        try {
            parser(tokens).query();
            return true;
        } catch (SyntaxError error) {
            return error.offending.getTokenIndex() >= index;
        }
    }

    // a token the grammar accepts nowhere: the parser is guaranteed to stop on it
    private static List<Token> withSentinel(List<Token> tokens) {
        List<Token> result = new ArrayList<>(tokens);
        result.add(new CommonToken(TqlLexer.ERROR_CHAR, "‸"));
        return result;
    }

    /**
     * Stop at the first mistake. BailErrorStrategy already skips ANTLR's recovery (no token deletion or
     * insertion, no resync), but throws a ParseCancellationException that only sometimes carries the token;
     * both of its exits are overridden to throw one exception that always does.
     */
    private static final class Bail extends BailErrorStrategy {

        // a rule found no alternative that fits (NoViableAlt) or a predicate failed
        @Override
        public void recover(Parser recognizer, RecognitionException e) {
            Token offending = e.getOffendingToken();
            throw new SyntaxError(offending == null ? recognizer.getCurrentToken() : offending);
        }

        // match() wanted one specific token and got another
        @Override
        public Token recoverInline(Parser recognizer) {
            throw new SyntaxError(recognizer.getCurrentToken());
        }
    }

    /** Internal: unwinds the parser's call stack; converted to a TqlException with a message by {@link #parse}. */
    private static final class SyntaxError extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Token offending;

        SyntaxError(Token offending) {
            super(null, null, false, false);
            this.offending = offending;
        }
    }
}

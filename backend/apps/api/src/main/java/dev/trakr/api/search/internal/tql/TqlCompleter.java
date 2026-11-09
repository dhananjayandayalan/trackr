package dev.trakr.api.search.internal.tql;

import dev.trakr.api.search.internal.tql.FieldRegistry.FieldSpec;
import dev.trakr.api.search.internal.tql.grammar.TqlLexer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.antlr.v4.runtime.Token;

/**
 * Autocomplete for the query editor (05-search §3): given the text and the caret, what could come next?
 *
 * <ol>
 * <li>Split off the word being typed ({@code sta|} → "sta"); suggestions replace it, starting at {@code from}.
 * <li>Ask the parser which token types may follow the text before it ({@link TqlParser#expectedAfter}).
 * <li>Turn token types into words, using where we are: after a field come its operators, after an
 *     operator the field's values, after a complete clause AND / OR / ORDER BY.
 * </ol>
 *
 * Pure: the values for a field (statuses, people, labels) come from a {@link Values} the caller passes.
 */
public final class TqlCompleter {

    /** One suggestion; {@code kind} is field, operator, value or keyword (03-api §3.8). */
    public record Suggestion(String kind, String text) {
    }

    /** {@code from}: where the replaced text starts — the word being typed, or the caret if there is none. */
    @Schema(name = "TqlCompletion")
    public record Result(int from, List<Suggestion> expected) {
    }

    /** The values worth offering for a field, unquoted ("In review", "currentUser()"); filtering is done here. */
    @FunctionalInterface
    public interface Values {
        List<String> of(FieldSpec field);
    }

    private static final int MAX = 50;
    private static final Set<Integer> PLAIN = Set.of(TqlLexer.IDENT, TqlLexer.ISSUEKEY, TqlLexer.NUMBER);
    private static final Set<Integer> OPERATORS = Set.of(TqlLexer.EQ, TqlLexer.NE, TqlLexer.GT, TqlLexer.GE,
            TqlLexer.LT, TqlLexer.LE, TqlLexer.TILDE, TqlLexer.IS);
    private static final Set<Integer> WORDS = Set.of(TqlLexer.IDENT, TqlLexer.ISSUEKEY, TqlLexer.AND, TqlLexer.OR,
            TqlLexer.NOT, TqlLexer.IN, TqlLexer.IS, TqlLexer.EMPTY, TqlLexer.ORDER, TqlLexer.BY, TqlLexer.ASC,
            TqlLexer.DESC);

    private TqlCompleter() {
    }

    public static Result complete(String tql, int caret, Values values) {
        String text = tql.substring(0, Math.clamp(caret, 0, tql.length()));

        // BLOCK 1: the word being typed. Inside an unclosed quote it's everything after the quote.
        int quote = unclosedQuote(text);
        List<Token> prefix;
        String partial;
        int from;
        if (quote >= 0) {
            prefix = TqlParser.tokens(text.substring(0, quote));
            partial = text.substring(quote + 1);
            from = quote;
        } else {
            List<Token> tokens = TqlParser.tokens(text);
            Token last = tokens.isEmpty() ? null : tokens.getLast();
            if (last != null && last.getStopIndex() + 1 == text.length() && isPartial(last)) {
                prefix = tokens.subList(0, tokens.size() - 1);
                partial = last.getText();
                from = last.getStartIndex();
            } else {
                prefix = tokens;
                partial = "";
                from = text.length();
            }
        }

        // BLOCK 2: what may follow — nothing to suggest if the text before is already broken
        if (!TqlParser.isViablePrefix(prefix)) {
            return new Result(from, List.of());
        }
        Set<Integer> expected = TqlParser.expectedAfter(prefix);

        // BLOCK 3: token types → words
        List<Suggestion> suggestions = new ArrayList<>();
        int lastType = prefix.isEmpty() ? Token.INVALID_TYPE : prefix.getLast().getType();
        if ((expected.contains(TqlLexer.STRING) && lastType != TqlLexer.TILDE) || lastType == TqlLexer.IN) {
            // a value position (right after IN: a function standing for a list); after ~ comes free text
            valueField(prefix).ifPresent(field -> values.of(field).stream()
                    .filter(value -> lastType != TqlLexer.IN || value.contains("("))
                    .forEach(value -> suggestions.add(new Suggestion("value", quote >= 0 ? quoted(value) : asTql(value)))));
        } else if (lastType == TqlLexer.IDENT && expected.stream().anyMatch(OPERATORS::contains)) {
            // right after a field: its operators
            FieldRegistry.find(prefix.getLast().getText()).ifPresent(field -> field.operatorTexts()
                    .forEach(op -> suggestions.add(new Suggestion("operator", op))));
        } else if (lastType == TqlLexer.IS) {
            suggestions.add(new Suggestion("keyword", "empty"));
            suggestions.add(new Suggestion("keyword", "not empty"));
        } else if (lastType == TqlLexer.NOT && expected.contains(TqlLexer.IN)) {
            suggestions.add(new Suggestion("operator", "in"));
        } else {
            boolean ordering = prefix.stream().anyMatch(token -> token.getType() == TqlLexer.ORDER);
            if (expected.contains(TqlLexer.IDENT)) {
                (ordering ? FieldRegistry.sortable() : FieldRegistry.queryable())
                        .forEach(field -> suggestions.add(new Suggestion("field", field.name())));
            }
            for (int type : expected) {
                keyword(type, ordering).ifPresent(word -> suggestions.add(new Suggestion("keyword", word)));
            }
        }

        // BLOCK 4: keep what starts with the typed word ("ada" also finds "Ada Lovelace" and "Grace Ada")
        String typed = partial.toLowerCase(Locale.ROOT);
        Set<Suggestion> matching = new LinkedHashSet<>();
        for (Suggestion suggestion : suggestions) {
            String candidate = suggestion.text().toLowerCase(Locale.ROOT).replace("\"", "");
            if (candidate.startsWith(typed) || candidate.contains(" " + typed)) {
                matching.add(suggestion);
            }
        }
        return new Result(from, matching.stream().limit(MAX).toList());
    }

    // the field whose value comes next: before the operator, or before IN (and NOT) when inside a list
    private static Optional<FieldSpec> valueField(List<Token> prefix) {
        int i = prefix.size() - 1;
        if (i < 0) {
            return Optional.empty();
        }
        int type = prefix.get(i).getType();
        if (OPERATORS.contains(type) || type == TqlLexer.IN) {
            int fieldAt = type == TqlLexer.IN && i > 0 && prefix.get(i - 1).getType() == TqlLexer.NOT ? i - 2 : i - 1;
            return fieldAt < 0 ? Optional.empty() : FieldRegistry.find(prefix.get(fieldAt).getText());
        }
        // inside ( … , …: back to the opening parenthesis
        while (i >= 0 && prefix.get(i).getType() != TqlLexer.LPAREN) {
            i--;
        }
        if (i < 1) {
            return Optional.empty();
        }
        Token before = prefix.get(i - 1);
        if (before.getType() == TqlLexer.IN) {
            int fieldAt = i >= 3 && prefix.get(i - 2).getType() == TqlLexer.NOT ? i - 3 : i - 2;
            return fieldAt < 0 ? Optional.empty() : FieldRegistry.find(prefix.get(fieldAt).getText());
        }
        // membersOf( → project keys
        if (before.getType() == TqlLexer.IDENT && before.getText().equalsIgnoreCase("membersOf")) {
            return FieldRegistry.find("project");
        }
        return Optional.empty();
    }

    private static Optional<String> keyword(int type, boolean ordering) {
        return Optional.ofNullable(switch (type) {
            case TqlLexer.AND -> "AND";
            case TqlLexer.OR -> "OR";
            case TqlLexer.NOT -> ordering ? null : "NOT";
            case TqlLexer.ORDER -> "ORDER BY";
            case TqlLexer.BY -> "BY";
            case TqlLexer.ASC -> "ASC";
            case TqlLexer.DESC -> "DESC";
            default -> null;
        });
    }

    private static boolean isPartial(Token token) {
        // a word, or an operator that may still grow: ! → !=, < → <=, > → >=
        return WORDS.contains(token.getType()) || token.getType() == TqlLexer.LT || token.getType() == TqlLexer.GT
                || token.getText().equals("!");
    }

    // index of a quote that is opened but not closed before the caret, else -1
    private static int unclosedQuote(String text) {
        int open = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (open >= 0 && c == '\\') {
                i++;
            } else if (c == '"') {
                open = open >= 0 ? -1 : i;
            }
        }
        return open;
    }

    // MAR, Done, MAR-12, 3 and currentUser() stand alone; "In review", "Ada Lovelace" and a status called
    // "Empty" (a keyword) need quotes: a value is plain only if it lexes as exactly one name/key/number
    private static String asTql(String value) {
        if (value.endsWith(")")) {
            return value;
        }
        List<Token> tokens = TqlParser.tokens(value);
        boolean plain = tokens.size() == 1 && tokens.getFirst().getText().equals(value)
                && PLAIN.contains(tokens.getFirst().getType());
        return plain ? value : quoted(value);
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

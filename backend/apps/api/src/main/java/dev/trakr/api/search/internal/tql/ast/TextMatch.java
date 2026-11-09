package dev.trakr.api.search.internal.tql.ast;

/** {@code field ~ "words"}: full-text, scored, typo-tolerant — not an exact comparison. */
public record TextMatch(Field field, String text) implements Expr {

    @Override
    public String toString() {
        return field + " ~ " + Value.quote(text);
    }
}

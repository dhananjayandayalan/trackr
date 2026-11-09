package dev.trakr.api.search.internal.tql.ast;

/** {@code left OR right}. */
public record Or(Expr left, Expr right) implements Expr {

    @Override
    public String toString() {
        return "(" + left + " OR " + right + ")";
    }
}

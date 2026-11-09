package dev.trakr.api.search.internal.tql.ast;

/** {@code NOT expr}. */
public record Not(Expr expr) implements Expr {

    @Override
    public String toString() {
        return "NOT " + expr;
    }
}

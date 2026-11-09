package dev.trakr.api.search.internal.tql.ast;

/** {@code left AND right}. Binary like the grammar; EsQueryCompiler flattens chains into one bool query. */
public record And(Expr left, Expr right) implements Expr {

    @Override
    public String toString() {
        return "(" + left + " AND " + right + ")";
    }
}

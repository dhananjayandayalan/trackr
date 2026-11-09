package dev.trakr.api.search.internal.tql.ast;

/** {@code field op value}, e.g. {@code priority >= High}. */
public record Compare(Field field, Op op, Value value) implements Expr {

    @Override
    public String toString() {
        return field + " " + op + " " + value;
    }
}

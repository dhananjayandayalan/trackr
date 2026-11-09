package dev.trakr.api.search.internal.tql.ast;

/** {@code field IS EMPTY} or, negated, {@code field IS NOT EMPTY}. */
public record IsEmpty(Field field, boolean negated) implements Expr {

    @Override
    public String toString() {
        return field + (negated ? " IS NOT EMPTY" : " IS EMPTY");
    }
}

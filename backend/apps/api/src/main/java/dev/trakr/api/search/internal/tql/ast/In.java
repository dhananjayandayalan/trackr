package dev.trakr.api.search.internal.tql.ast;

import java.util.List;
import java.util.stream.Collectors;

/** {@code field IN (a, b)} or, negated, {@code field NOT IN (a, b)}. */
public record In(Field field, boolean negated, List<Value> values) implements Expr {

    public In {
        values = List.copyOf(values);
    }

    @Override
    public String toString() {
        return field + (negated ? " NOT IN (" : " IN (")
                + values.stream().map(Value::toString).collect(Collectors.joining(", ")) + ")";
    }
}

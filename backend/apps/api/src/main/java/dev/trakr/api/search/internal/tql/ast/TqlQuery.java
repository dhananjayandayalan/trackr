package dev.trakr.api.search.internal.tql.ast;

import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A whole parsed query: the condition (null for an empty query or one that is only an ORDER BY) and
 * the sort keys. Immutable, built once by AstBuilder; nothing after the parser sees ANTLR types.
 */
public record TqlQuery(@Nullable Expr where, List<OrderBy> orderBy) {

    public TqlQuery {
        orderBy = List.copyOf(orderBy);
    }

    @Override
    public String toString() {
        String sort = orderBy.isEmpty() ? ""
                : "ORDER BY " + orderBy.stream().map(OrderBy::toString).collect(Collectors.joining(", "));
        if (where == null) {
            return sort;
        }
        return sort.isEmpty() ? where.toString() : where + " " + sort;
    }
}

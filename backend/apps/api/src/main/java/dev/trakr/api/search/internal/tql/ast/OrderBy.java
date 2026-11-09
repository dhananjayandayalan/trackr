package dev.trakr.api.search.internal.tql.ast;

/** One {@code ORDER BY} key: {@code priority DESC}. Ascending unless DESC is written. */
public record OrderBy(Field field, boolean descending) {

    @Override
    public String toString() {
        return field + (descending ? " DESC" : " ASC");
    }
}

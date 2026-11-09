package dev.trakr.api.search.internal.tql.ast;

import java.util.Locale;

/**
 * A field name as written, plus where it starts in the query (0-based character offset) so a later
 * "unknown field" error can point at it. {@link #key()} is the lowercase name FieldRegistry looks up.
 */
public record Field(String name, int position) {

    public String key() {
        return name.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return name;
    }
}

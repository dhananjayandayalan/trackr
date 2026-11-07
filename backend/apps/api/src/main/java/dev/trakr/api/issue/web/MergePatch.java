package dev.trakr.api.issue.web;

import dev.trakr.api.issue.PatchIssue.Value;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Reads a JSON body bound to a Map, where "absent", "null" and "a value" are three different
 * answers (JSON merge patch). Collects every field's error before failing, so the client sees all
 * of them at once. Used by PATCH /issues/{key} and POST /issues/{key}/move.
 */
final class MergePatch {

    private final Map<String, @Nullable Object> body;
    private final List<FieldMessage> errors = new ArrayList<>();

    MergePatch(Map<String, @Nullable Object> body) {
        this.body = body;
    }

    /** A field that may be cleared with null: absent → null, null → Value(null). */
    <J, T> @Nullable Value<T> optional(String field, Class<J> jsonType, Function<J, @Nullable T> convert) {
        if (!body.containsKey(field)) {
            return null;
        }
        Object raw = body.get(field);
        if (raw == null) {
            return new Value<>(null);
        }
        return typed(field, raw, jsonType, convert);
    }

    /** A field that must have a value when present. */
    <J, T> @Nullable Value<T> required(String field, Class<J> jsonType, Function<J, @Nullable T> convert) {
        if (!body.containsKey(field)) {
            return null;
        }
        Object raw = body.get(field);
        if (raw == null) {
            errors.add(new FieldMessage(field, "cannot be cleared"));
            return null;
        }
        return typed(field, raw, jsonType, convert);
    }

    private <J, T> @Nullable Value<T> typed(String field, Object raw, Class<J> jsonType,
            Function<J, @Nullable T> convert) {
        if (!jsonType.isInstance(raw)) {
            errors.add(new FieldMessage(field, "has the wrong JSON type"));
            return null;
        }
        return new Value<>(convert.apply(jsonType.cast(raw)));
    }

    <T> @Nullable T parse(String field, String value, Function<String, T> parser, String message) {
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            errors.add(new FieldMessage(field, message));
            return null;
        }
    }

    void check(String field, boolean ok, String message) {
        if (!ok) {
            errors.add(new FieldMessage(field, message));
        }
    }

    void error(FieldMessage message) {
        errors.add(message);
    }

    void rejectUnknown(Set<String> allowed, String message) {
        body.keySet().stream().filter(field -> !allowed.contains(field))
                .forEach(field -> errors.add(new FieldMessage(field, message)));
    }

    void throwIfInvalid(String detail) {
        if (!errors.isEmpty()) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, errors);
        }
    }
}

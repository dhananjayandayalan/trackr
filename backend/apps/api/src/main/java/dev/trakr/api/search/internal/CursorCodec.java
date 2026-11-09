package dev.trakr.api.search.internal;

import co.elastic.clients.elasticsearch._types.FieldValue;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.TrakrException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The opaque {@code nextCursor} of /search: the point-in-time id and the last hit's sort values, as
 * JSON, base64url-encoded (03-api §1). The client sends it back unchanged; it is only a position, never
 * a permission — the query and the security filter are rebuilt from scratch on every page.
 */
public final class CursorCodec {

    /** {@code pitId}: the frozen view of the index; {@code after}: the sort values of the last hit seen. */
    public record Cursor(String pitId, List<FieldValue> after) {

        public Cursor {
            after = List.copyOf(after);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CursorCodec() {
    }

    public static String encode(Cursor cursor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pit", cursor.pitId());
        body.put("after", cursor.after().stream().map(CursorCodec::plain).toList());
        return CursorPage.encode(JSON.writeValueAsString(body));
    }

    public static Cursor decode(String cursor) {
        try {
            Map<?, ?> body = JSON.readValue(CursorPage.decode(cursor), Map.class);
            if (!(body.get("pit") instanceof String pit) || !(body.get("after") instanceof List<?> after)) {
                throw invalid();
            }
            List<FieldValue> values = new ArrayList<>(after.size());
            for (Object value : after) {
                values.add(fieldValue(value));
            }
            return new Cursor(pit, values);
        } catch (JacksonException e) {
            throw invalid();
        }
    }

    // sort values are numbers (dates come back as epoch millis, _score as a double), strings, booleans or
    // null (a missing value); Stream.toList() keeps nulls, so they round-trip as JSON null
    private static @Nullable Object plain(FieldValue value) {
        return switch (value._kind()) {
            case Long -> value.longValue();
            case Double -> value.doubleValue();
            case Boolean -> value.booleanValue();
            case String -> value.stringValue();
            case Null -> null;
            case Any -> value.anyValue().to(Object.class);
        };
    }

    // unbox explicitly: FieldValue.of(Long) would pick the of(Object) overload and make an untyped "Any" value
    private static FieldValue fieldValue(@Nullable Object value) {
        return switch (value) {
            case null -> FieldValue.NULL;
            case Integer i -> FieldValue.of(i.longValue());
            case Long l -> FieldValue.of(l.longValue());
            case BigInteger b -> FieldValue.of(b.longValueExact());
            case Double d -> FieldValue.of(d.doubleValue());
            case BigDecimal d -> FieldValue.of(d.doubleValue());
            case Boolean b -> FieldValue.of(b.booleanValue());
            case String s -> FieldValue.of(s);
            default -> throw invalid();
        };
    }

    private static TrakrException invalid() {
        return new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The cursor is not valid.");
    }
}

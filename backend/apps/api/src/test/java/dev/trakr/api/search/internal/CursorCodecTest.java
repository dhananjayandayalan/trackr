package dev.trakr.api.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch._types.FieldValue;
import dev.trakr.api.search.internal.CursorCodec.Cursor;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.TrakrException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    @Test
    void roundTripsEveryKindOfSortValue() {
        // what search_after gets back: _score (double), a date (epoch millis), a keyword, a missing value
        Cursor cursor = new Cursor("46ToAwMDaWR5BXV1aWQyKwZub2RlXzMAAAAAAAAAACoBYwADaWR4BXV1aWQxAgZub2RlXzEAAAAAAAAAAAEBYQADaWR5",
                List.of(FieldValue.of(1.2345678901234567), FieldValue.of(1762678800000L), FieldValue.of("MAR"),
                        FieldValue.of(true), FieldValue.NULL));

        String encoded = CursorCodec.encode(cursor);
        Cursor decoded = CursorCodec.decode(encoded);

        // base64url: safe in a URL or JSON string without escaping
        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(decoded.pitId()).isEqualTo(cursor.pitId());
        assertThat(decoded.after()).hasSize(5);
        assertThat(decoded.after().get(0).doubleValue()).isEqualTo(1.2345678901234567);
        assertThat(decoded.after().get(1).longValue()).isEqualTo(1762678800000L);
        assertThat(decoded.after().get(2).stringValue()).isEqualTo("MAR");
        assertThat(decoded.after().get(3).booleanValue()).isTrue();
        assertThat(decoded.after().get(4).isNull()).isTrue();
    }

    @Test
    void anythingElseIsA400() {
        assertInvalid("not base64 at all!");
        assertInvalid(CursorPage.encode("not json"));
        assertInvalid(CursorPage.encode("{\"after\": []}"));
        assertInvalid(CursorPage.encode("{\"pit\": \"x\", \"after\": [{\"nested\": 1}]}"));
    }

    private static void assertInvalid(String cursor) {
        assertThatThrownBy(() -> CursorCodec.decode(cursor))
                .isInstanceOfSatisfying(TrakrException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    }
}

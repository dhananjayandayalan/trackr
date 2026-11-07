package dev.trakr.api.shared.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * A page of a cursor-paged list (03-api §1): {@code {items, nextCursor}}. The cursor is the last
 * row's sort key, base64url-encoded so clients treat it as opaque; null when there is no next page.
 */
public record CursorPage<T>(List<T> items, @Nullable String nextCursor) {

    public CursorPage {
        items = List.copyOf(items);
    }

    public static String encode(String sortKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sortKey.getBytes(StandardCharsets.UTF_8));
    }

    /** The sort key inside a cursor; 400 when the client sent something we never handed out. */
    public static String decode(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The cursor is not valid.");
        }
    }
}

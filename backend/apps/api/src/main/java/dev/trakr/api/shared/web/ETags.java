package dev.trakr.api.shared.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * ETags carry the optimistic-lock version: {@code ETag: "v12"}. A client edits with
 * {@code If-Match: "v12"}; if the row is at version 13 by then, the edit is refused with 412
 * instead of silently overwriting someone else's change (the "lost update").
 */
public final class ETags {

    // "v12" or W/"v12" (a proxy may weaken it when compressing)
    private static final Pattern VERSION = Pattern.compile("^(?:W/)?\"v(\\d+)\"$");

    private ETags() {
    }

    public static String of(long version) {
        return "\"v" + version + "\"";
    }

    /** The version the client last saw, from If-Match; 428 when the header is missing or not one of ours. */
    public static long versionFrom(@Nullable String ifMatch) {
        Matcher matcher = ifMatch == null ? null : VERSION.matcher(ifMatch.strip());
        if (matcher == null || !matcher.matches()) {
            throw new TrakrException(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                    "Send If-Match with the ETag you last received, e.g. If-Match: \"v3\".");
        }
        return Long.parseLong(matcher.group(1));
    }

    /** Throws 412 with {@code current} when the client's version is not the current one. */
    public static void requireMatch(long expectedVersion, long currentVersion, Object current) {
        if (expectedVersion != currentVersion) {
            throw new TrakrException.VersionMismatch(current);
        }
    }
}

package dev.trakr.api.sprint;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code epicId}: only that epic's issues; {@code type}: an issue type value; {@code q}: text in key or summary. */
public record BacklogFilter(@Nullable UUID epicId, @Nullable String type, @Nullable String q) {

    public static BacklogFilter none() {
        return new BacklogFilter(null, null, null);
    }
}

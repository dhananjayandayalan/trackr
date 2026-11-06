package dev.trakr.api.project;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A JSON merge patch of a project (03-api §1): a null component means "absent, leave it alone".
 * Only {@code description} may be cleared, so it alone needs a third state, {@code descriptionPresent}:
 * present + null description = clear it.
 */
public record ProjectPatch(@Nullable String name, boolean descriptionPresent, @Nullable String description,
        @Nullable UUID leadId, @Nullable Access access, @Nullable String color) {
}

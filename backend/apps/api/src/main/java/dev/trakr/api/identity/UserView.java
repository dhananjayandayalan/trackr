package dev.trakr.api.identity;

import java.util.UUID;

/** A user as other code sees them: the entity stays inside the module. */
public record UserView(UUID id, String email, String displayName, String initials, String avatarColor) {
}

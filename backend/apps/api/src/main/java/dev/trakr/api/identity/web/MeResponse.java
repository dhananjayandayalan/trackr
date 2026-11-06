package dev.trakr.api.identity.web;

import dev.trakr.api.identity.UserView;
import dev.trakr.api.workspace.CurrentWorkspace;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** 03-api §3.1 {@code Me}: returned by GET /me, signup and login. */
record MeResponse(UUID id, String email, String displayName, String initials, String avatarColor,
        @Nullable Workspace workspace) {

    record Workspace(UUID id, String name, String slug, String role) {
    }

    static MeResponse of(UserView user, @Nullable CurrentWorkspace workspace) {
        return new MeResponse(user.id(), user.email(), user.displayName(), user.initials(), user.avatarColor(),
                workspace == null ? null
                        : new Workspace(workspace.id(), workspace.name(), workspace.slug(), workspace.role()));
    }
}

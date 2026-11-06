package dev.trakr.api.project;

import java.util.UUID;

public record MemberView(UUID userId, String displayName, String initials, String avatarColor, ProjectRole role) {
}

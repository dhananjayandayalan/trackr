package dev.trakr.api.workspace;

import java.util.UUID;

/** The workspace a user works in, and their role there ({@code owner} or {@code member}). */
public record CurrentWorkspace(UUID id, String name, String slug, String role) {
}

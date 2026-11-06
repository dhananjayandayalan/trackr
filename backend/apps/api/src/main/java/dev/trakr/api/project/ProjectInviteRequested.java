package dev.trakr.api.project;

import java.util.UUID;

/** Someone was invited by an email that has no account yet; the workers' mailer sends the invitation (phase 10). */
public record ProjectInviteRequested(UUID projectId, String email, ProjectRole role, UUID invitedBy) {
}

package dev.trakr.api.attachment;

import java.util.UUID;

/** A file was removed from an issue. In-process only, like AttachmentAdded. */
public record AttachmentRemoved(UUID attachmentId, UUID issueId, UUID projectId, UUID actorId) {
}

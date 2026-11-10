package dev.trakr.api.attachment;

import java.util.UUID;

/**
 * A file was added to an issue (01-architecture §3). In-process only, like ProjectCreated: it isn't in the
 * Kafka event catalogue (04-events §4), so it never leaves the api. Modules that care can listen to it.
 */
public record AttachmentAdded(UUID attachmentId, UUID issueId, UUID projectId, UUID actorId, String fileName) {
}

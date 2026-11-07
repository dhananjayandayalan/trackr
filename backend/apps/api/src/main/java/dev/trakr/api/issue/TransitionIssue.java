package dev.trakr.api.issue;

import java.util.UUID;

/** POST /issues/{key}/transition: the version travels in the body here, not in If-Match (03-api §3.4). */
public record TransitionIssue(UUID toStatusId, long expectedVersion) {
}

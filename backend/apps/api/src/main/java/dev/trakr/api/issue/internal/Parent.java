package dev.trakr.api.issue.internal;

import dev.trakr.api.issue.IssueType;
import java.util.UUID;

/** What Issue needs to know about a (would-be) parent to check 00-product §4.2. */
public record Parent(UUID id, IssueType type, UUID projectId) {
}

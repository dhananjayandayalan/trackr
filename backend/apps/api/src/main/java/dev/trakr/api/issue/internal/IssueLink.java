package dev.trakr.api.issue.internal;

import dev.trakr.api.issue.LinkType;
import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** "source {@code type} target", e.g. MAR-1 blocks MAR-2. Created and deleted, never changed. */
@Entity
@Table(name = "issue_link")
public class IssueLink {

    @Id
    private UUID id;
    private UUID sourceIssueId;
    private UUID targetIssueId;
    private String type;
    private UUID createdBy;
    private Instant createdAt;

    protected IssueLink() {
        // for JPA
    }

    public static IssueLink create(UUID sourceIssueId, UUID targetIssueId, LinkType type, UUID createdBy, Clock clock) {
        IssueLink link = new IssueLink();
        link.id = Ids.newId();
        link.sourceIssueId = sourceIssueId;
        link.targetIssueId = targetIssueId;
        link.type = type.value();
        link.createdBy = createdBy;
        link.createdAt = clock.instant();
        return link;
    }

    public UUID id() {
        return id;
    }

    public UUID sourceIssueId() {
        return sourceIssueId;
    }

    public UUID targetIssueId() {
        return targetIssueId;
    }

    public LinkType type() {
        return LinkType.fromValue(type);
    }
}

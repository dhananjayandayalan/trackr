package dev.trakr.api.issue.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One row of an issue's history (issue_event). Append-only: {@code @Immutable} tells Hibernate never
 * to write an UPDATE for it, and there are no setters. Read back through IssueQueries.history.
 */
@Entity
@Table(name = "issue_event")
@Immutable
public class IssueEventRecord {

    public enum Kind {
        CREATED, FIELD_CHANGED, TRANSITIONED, LINKED, LOGGED_WORK, DELETED;

        String value() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    // bigint identity: Postgres numbers the rows, so Hibernate must INSERT at persist() to learn the id
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;
    private UUID issueId;
    private UUID actorId;
    private Instant occurredAt;
    private String kind;
    private @Nullable String field;
    // already-serialised JSON: for a String attribute Hibernate passes the text through to jsonb untouched
    @JdbcTypeCode(SqlTypes.JSON)
    private @Nullable String oldValue;
    @JdbcTypeCode(SqlTypes.JSON)
    private @Nullable String newValue;

    protected IssueEventRecord() {
        // for JPA
    }

    static IssueEventRecord of(UUID issueId, UUID actorId, Instant occurredAt, Kind kind, @Nullable String field,
            @Nullable String oldValueJson, @Nullable String newValueJson) {
        IssueEventRecord event = new IssueEventRecord();
        event.issueId = issueId;
        event.actorId = actorId;
        event.occurredAt = occurredAt;
        event.kind = kind.value();
        event.field = field;
        event.oldValue = oldValueJson;
        event.newValue = newValueJson;
        return event;
    }
}

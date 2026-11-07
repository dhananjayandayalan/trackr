package dev.trakr.api.issue.internal;

import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Time someone spent on an issue; the total also lives on the issue (time_spent_min) for cheap reads. */
@Entity
@Table(name = "worklog")
public class Worklog {

    @Id
    private UUID id;
    private UUID issueId;
    private UUID userId;
    private Instant startedAt;
    private int minutes;
    private @Nullable String note;

    protected Worklog() {
        // for JPA
    }

    public static Worklog log(UUID issueId, UUID userId, Instant startedAt, int minutes, @Nullable String note) {
        Worklog worklog = new Worklog();
        worklog.id = Ids.newId();
        worklog.issueId = issueId;
        worklog.userId = userId;
        worklog.startedAt = startedAt;
        worklog.minutes = minutes;
        worklog.note = note == null || note.isBlank() ? null : note.strip();
        return worklog;
    }

    public UUID id() {
        return id;
    }

    public int minutes() {
        return minutes;
    }
}

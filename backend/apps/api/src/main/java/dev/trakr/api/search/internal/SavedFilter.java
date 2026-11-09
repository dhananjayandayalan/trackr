package dev.trakr.api.search.internal;

import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A TQL query someone kept under a name (02-data-model: saved_filter). Only the owner changes it;
 * {@code shared} makes it visible, read-only, to everyone in the owner's workspace.
 */
@Entity
@Table(name = "saved_filter")
public class SavedFilter {

    private static final int MAX_NAME = 80;

    @Id
    private UUID id;
    private UUID workspaceId;
    private UUID ownerId;
    private String name;
    private String tql;
    private boolean starred;
    private boolean shared;
    private Instant createdAt;

    protected SavedFilter() {
        // for JPA
    }

    /** The TQL must have been checked already (SearchService does it); this only checks the name. */
    public static SavedFilter create(UUID workspaceId, UUID ownerId, String name, String tql, boolean starred,
            boolean shared, Clock clock) {
        SavedFilter filter = new SavedFilter();
        filter.id = Ids.newId();
        filter.workspaceId = workspaceId;
        filter.ownerId = ownerId;
        filter.rename(name);
        filter.tql = tql;
        filter.starred = starred;
        filter.shared = shared;
        filter.createdAt = clock.instant();
        return filter;
    }

    public boolean ownedBy(UUID userId) {
        return ownerId.equals(userId);
    }

    public void rename(String name) {
        String trimmed = name.strip();
        // the same rule as ck_saved_filter_name, so the client gets a field error instead of a 409
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The filter needs a name.",
                    List.of(new FieldMessage("name", "must be 1 to " + MAX_NAME + " characters")));
        }
        this.name = trimmed;
    }

    public void changeQuery(String tql) {
        this.tql = tql;
    }

    public void star(boolean starred) {
        this.starred = starred;
    }

    public void share(boolean shared) {
        this.shared = shared;
    }

    public UUID id() {
        return id;
    }

    public UUID workspaceId() {
        return workspaceId;
    }
}

package dev.trakr.api.workspace.internal;

import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "workspace")
public class Workspace {

    @Id
    private UUID id;
    private String slug;
    private String name;
    private Instant createdAt;

    protected Workspace() {
        // for JPA
    }

    /** The workspace every new user gets: "Ada Lovelace" → "Ada Lovelace's workspace", slug ada-lovelace-3f9a1c. */
    public static Workspace personalFor(String ownerName, Clock clock) {
        Workspace workspace = new Workspace();
        workspace.id = Ids.newId();
        workspace.slug = slugOf(ownerName, workspace.id);
        workspace.name = ownerName + "'s workspace";
        workspace.createdAt = clock.instant();
        return workspace;
    }

    // must match ^[a-z0-9-]{3,40}$; the last 6 hex digits of the id are random bits, so two Adas get different slugs
    static String slugOf(String name, UUID id) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "workspace";
        }
        String idString = id.toString();
        return base.substring(0, Math.min(base.length(), 33)) + "-" + idString.substring(idString.length() - 6);
    }

    public UUID id() {
        return id;
    }

    public String slug() {
        return slug;
    }

    public String name() {
        return name;
    }
}

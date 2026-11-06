package dev.trakr.api.identity.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One emailed reset link. Only the SHA-256 of the token is stored, so a database leak can't reset passwords. */
@Entity
@Table(name = "password_reset")
public class PasswordReset {

    @Id
    private String tokenHash;
    private UUID userId;
    private Instant expiresAt;
    private @Nullable Instant usedAt;

    protected PasswordReset() {
        // for JPA
    }

    public static PasswordReset issue(String tokenHash, UUID userId, Instant expiresAt) {
        PasswordReset reset = new PasswordReset();
        reset.tokenHash = tokenHash;
        reset.userId = userId;
        reset.expiresAt = expiresAt;
        return reset;
    }

    /** Single use, and only until it expires. */
    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        usedAt = now;
    }

    public UUID userId() {
        return userId;
    }
}

package dev.trakr.api.identity.internal;

import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** A Google or GitHub account linked to a user: (provider, subject) is the provider's id for that person. */
@Entity
@Table(name = "user_identity")
public class UserIdentity {

    @Id
    private UUID id;
    private UUID userId;
    private String provider;
    private String subject;
    private Instant createdAt;

    protected UserIdentity() {
        // for JPA
    }

    public static UserIdentity link(UUID userId, String provider, String subject, Clock clock) {
        UserIdentity identity = new UserIdentity();
        identity.id = Ids.newId();
        identity.userId = userId;
        identity.provider = provider;
        identity.subject = subject;
        identity.createdAt = clock.instant();
        return identity;
    }

    public UUID userId() {
        return userId;
    }
}

package dev.trakr.api.identity.internal;

import dev.trakr.api.identity.UserView;
import dev.trakr.kernel.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

// BLOCK 1: entity mapping
@Entity
@Table(name = "app_user")
public class AppUser {

    // the VOLT avatar tokens, in the order of the CHECK constraint
    private static final List<String> AVATAR_COLORS = List.of("pink", "cyan", "lime", "yellow", "orange", "violet");

    @Id
    private UUID id;
    // tells Hibernate's schema validation the column is citext, not varchar
    @Column(columnDefinition = "citext")
    private String email;
    private String displayName;
    private String initials;
    private String avatarColor;
    private @Nullable String passwordHash;
    private @Nullable Instant emailVerifiedAt;
    private Instant createdAt;

    protected AppUser() {
        // for JPA
    }

    // BLOCK 2: factory
    /** A new user; {@code passwordHash} is null for users who only sign in with Google or GitHub. */
    public static AppUser register(String email, String displayName, @Nullable String passwordHash, Clock clock) {
        AppUser user = new AppUser();
        user.id = Ids.newId();
        user.email = email;
        user.displayName = displayName;
        user.initials = initialsOf(displayName);
        // same id → same colour, every time
        user.avatarColor = AVATAR_COLORS.get(Math.floorMod(user.id.hashCode(), AVATAR_COLORS.size()));
        user.passwordHash = passwordHash;
        user.createdAt = clock.instant();
        return user;
    }

    // "Ada Lovelace" → "AL", "ada" → "A"; works per code point, so one letter stays one character
    static String initialsOf(String displayName) {
        String[] words = displayName.strip().split("\\s+");
        StringBuilder initials = new StringBuilder().appendCodePoint(Character.toUpperCase(words[0].codePointAt(0)));
        if (words.length > 1) {
            initials.appendCodePoint(Character.toUpperCase(words[words.length - 1].codePointAt(0)));
        }
        return initials.toString();
    }

    // BLOCK 3: behaviour — no setters; changes happen through methods that say what they mean
    public void changePassword(String newHash) {
        this.passwordHash = newHash;
    }

    public void markEmailVerified(Clock clock) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = clock.instant();
        }
    }

    public UserView toView() {
        return new UserView(id, email, displayName, initials, avatarColor);
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public @Nullable String passwordHash() {
        return passwordHash;
    }

    public @Nullable Instant emailVerifiedAt() {
        return emailVerifiedAt;
    }
}

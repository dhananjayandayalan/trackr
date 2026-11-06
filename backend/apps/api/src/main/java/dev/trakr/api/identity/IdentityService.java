package dev.trakr.api.identity;

import dev.trakr.api.identity.internal.AppUser;
import dev.trakr.api.identity.internal.AppUserRepository;
import dev.trakr.api.identity.internal.PasswordPolicy;
import dev.trakr.api.identity.internal.UserIdentity;
import dev.trakr.api.identity.internal.UserIdentityRepository;
import dev.trakr.api.workspace.WorkspaceService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The identity module's public API: who users are, and how they get an account. */
@Service
public class IdentityService {

    private final AppUserRepository users;
    private final UserIdentityRepository identities;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final WorkspaceService workspaces;
    private final Clock clock;

    IdentityService(AppUserRepository users, UserIdentityRepository identities, PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder, WorkspaceService workspaces, Clock clock) {
        this.users = users;
        this.identities = identities;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /** A new password account plus its personal workspace, in one transaction: both exist or neither. */
    @Transactional
    public UserView signUp(String name, String email, String password) {
        List<String> violations = passwordPolicy.violations(password);
        if (!violations.isEmpty()) {
            throw new WeakPasswordException(violations);
        }
        if (users.existsByEmail(email)) {
            throw new EmailTakenException();
        }
        AppUser user = users.save(AppUser.register(email, name.strip(), passwordEncoder.encode(password), clock));
        workspaces.createPersonalWorkspace(user.id(), user.displayName());
        return user.toView();
    }

    @Transactional(readOnly = true)
    public Optional<UserView> findById(UUID id) {
        return users.findById(id).map(AppUser::toView);
    }

    /** Case-insensitive, like every email comparison here (citext). */
    @Transactional(readOnly = true)
    public Optional<UserView> findByEmail(String email) {
        return users.findByEmail(email).map(AppUser::toView);
    }

    /**
     * The user behind a Google or GitHub login. Rules, in order: a provider account linked before
     * wins; else a <em>verified</em> email links to the account with that email; else a new user and
     * workspace. An unverified email that matches an account is refused — anyone can type any email
     * into a provider profile, so trusting it would let them take over that account.
     */
    @Transactional
    public UserView linkOrCreateFromOAuth(String provider, String subject, @Nullable String email,
            boolean emailVerified, @Nullable String name) {
        Optional<UserIdentity> linked = identities.findByProviderAndSubject(provider, subject);
        if (linked.isPresent()) {
            return users.findById(linked.get().userId()).orElseThrow().toView();
        }
        if (email == null) {
            throw new AccountLinkRefusedException(provider + " did not share an email address");
        }

        Optional<AppUser> sameEmail = users.findByEmail(email);
        AppUser user;
        if (sameEmail.isPresent()) {
            if (!emailVerified) {
                throw new AccountLinkRefusedException("an account with this email exists, and " + provider
                        + " has not verified the email");
            }
            user = sameEmail.get();
        } else {
            String displayName = name == null || name.isBlank() ? email.substring(0, email.indexOf('@')) : name.strip();
            user = users.save(AppUser.register(email, displayName, null, clock));
            workspaces.createPersonalWorkspace(user.id(), user.displayName());
        }
        if (emailVerified) {
            user.markEmailVerified(clock);
        }
        identities.save(UserIdentity.link(user.id(), provider, subject, clock));
        return user.toView();
    }
}

package dev.trakr.api.identity.internal;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** How DaoAuthenticationProvider finds the account for an email at password login. */
@Service
class TrakrUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    TrakrUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    // OAuth-only users have no password, so for password login they don't exist; the provider turns
    // "not found" into the same "bad credentials" as a wrong password, so callers can't probe emails
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(email)
                .filter(user -> user.passwordHash() != null)
                .map(TrakrPrincipal::withPasswordHash)
                .orElseThrow(() -> new UsernameNotFoundException("no password account for this email"));
    }
}

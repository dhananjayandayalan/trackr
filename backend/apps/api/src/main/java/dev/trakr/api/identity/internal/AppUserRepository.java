package dev.trakr.api.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    // email is citext, but the JDBC driver binds Java strings as varchar, and citext = varchar falls back
    // to plain text equality, which is case-sensitive. The cast makes Postgres use citext's equality.
    @Query(value = "SELECT * FROM app_user WHERE email = CAST(:email AS citext)", nativeQuery = true)
    Optional<AppUser> findByEmail(String email);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM app_user WHERE email = CAST(:email AS citext))", nativeQuery = true)
    boolean existsByEmail(String email);
}

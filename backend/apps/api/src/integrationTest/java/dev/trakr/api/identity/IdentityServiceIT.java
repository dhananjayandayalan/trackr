package dev.trakr.api.identity;

import static dev.trakr.api.jooq.Tables.APP_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.workspace.CurrentWorkspace;
import dev.trakr.api.workspace.WorkspaceService;
import dev.trakr.kernel.Ids;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// not @Transactional: WorkspaceService reads with jOOQ, which only sees rows that were committed
// (or flushed by Hibernate). Each test uses its own email instead of rolling back.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdentityServiceIT {

    @Autowired
    IdentityService identityService;

    @Autowired
    WorkspaceService workspaceService;

    @Autowired
    DSLContext dsl;

    @Test
    void signUpCreatesUserPersonalWorkspaceAndOwnerMembership() {
        String email = uniqueEmail();

        UserView user = identityService.signUp("Ada Lovelace", email, "correct-horse-7");

        assertThat(user.email()).isEqualTo(email);
        assertThat(user.initials()).isEqualTo("AL");
        assertThat(identityService.findById(user.id())).contains(user);
        CurrentWorkspace workspace = workspaceService.currentFor(user.id()).orElseThrow();
        assertThat(workspace.name()).isEqualTo("Ada Lovelace's workspace");
        assertThat(workspace.slug()).startsWith("ada-lovelace-");
        assertThat(workspace.role()).isEqualTo("owner");
    }

    @Test
    void passwordIsStoredAsAnArgon2idHash() {
        UserView user = identityService.signUp("Ada", uniqueEmail(), "correct-horse-7");

        String hash = dsl.select(APP_USER.PASSWORD_HASH).from(APP_USER).where(APP_USER.ID.eq(user.id()))
                .fetchOne(APP_USER.PASSWORD_HASH);
        assertThat(hash).startsWith("$argon2id$").doesNotContain("correct-horse-7");
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCase() {
        String email = uniqueEmail();
        identityService.signUp("Ada", email, "correct-horse-7");

        assertThatThrownBy(() -> identityService.signUp("Ada again", email.toUpperCase(), "correct-horse-7"))
                .isInstanceOf(EmailTakenException.class);
    }

    @Test
    void weakPasswordIsRejectedAndNothingIsSaved() {
        String email = uniqueEmail();

        assertThatThrownBy(() -> identityService.signUp("Ada", email, "short"))
                .isInstanceOf(WeakPasswordException.class);
        // the email is still free, so the rejected attempt left no user behind
        assertThat(identityService.signUp("Ada", email, "correct-horse-7").email()).isEqualTo(email);
    }

    static String uniqueEmail() {
        return "user-" + Ids.newId() + "@example.com";
    }
}

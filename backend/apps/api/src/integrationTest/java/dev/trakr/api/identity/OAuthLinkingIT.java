package dev.trakr.api.identity;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static dev.trakr.jooq.Tables.APP_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.workspace.WorkspaceService;
import dev.trakr.kernel.Ids;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// the link/create rules of IdentityService.linkOrCreateFromOAuth, on the real service and a real database:
// nothing we own is mocked, and no Google or GitHub is needed
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OAuthLinkingIT {

    private final IdentityService identityService;
    private final WorkspaceService workspaceService;
    private final DSLContext dsl;

    @Autowired
    OAuthLinkingIT(IdentityService identityService, WorkspaceService workspaceService, DSLContext dsl) {
        this.identityService = identityService;
        this.workspaceService = workspaceService;
        this.dsl = dsl;
    }

    @Test
    void newProviderAccountCreatesVerifiedUserAndWorkspace() {
        String email = uniqueEmail();

        UserView user = identityService.linkOrCreateFromOAuth("google", subject(), email, true, "Grace Hopper");

        assertThat(user.displayName()).isEqualTo("Grace Hopper");
        assertThat(workspaceService.currentFor(user.id())).isPresent();
        assertThat(emailVerifiedAtOf(user)).isNotNull();
    }

    @Test
    void sameProviderAccountSignsInAsTheSameUser() {
        String subject = subject();
        UserView first = identityService.linkOrCreateFromOAuth("github", subject, uniqueEmail(), true, "Grace");

        // even if the email on the GitHub profile changed since
        UserView second = identityService.linkOrCreateFromOAuth("github", subject, uniqueEmail(), true, "Grace");

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void verifiedEmailLinksToTheExistingPasswordAccount() {
        String email = uniqueEmail();
        UserView passwordUser = identityService.signUp("Ada", email, "correct-horse-7");

        UserView viaGoogle = identityService.linkOrCreateFromOAuth("google", subject(), email.toUpperCase(), true, "Ada L");

        assertThat(viaGoogle.id()).isEqualTo(passwordUser.id());
        assertThat(emailVerifiedAtOf(viaGoogle)).isNotNull();
    }

    @Test
    void unverifiedEmailOfAnExistingAccountIsRefused() {
        String email = uniqueEmail();
        identityService.signUp("Ada", email, "correct-horse-7");

        assertThatThrownBy(() -> identityService.linkOrCreateFromOAuth("github", subject(), email, false, "Mallory"))
                .isInstanceOf(AccountLinkRefusedException.class);
    }

    @Test
    void unverifiedEmailNobodyUsesCreatesAnUnverifiedUser() {
        UserView user = identityService.linkOrCreateFromOAuth("github", subject(), uniqueEmail(), false, null);

        assertThat(user.displayName()).startsWith("user-");
        assertThat(emailVerifiedAtOf(user)).isNull();
    }

    @Test
    void providerWithoutEmailIsRefused() {
        assertThatThrownBy(() -> identityService.linkOrCreateFromOAuth("github", subject(), null, false, "Ghost"))
                .isInstanceOf(AccountLinkRefusedException.class);
    }

    private @Nullable OffsetDateTime emailVerifiedAtOf(UserView user) {
        return dsl.select(APP_USER.EMAIL_VERIFIED_AT).from(APP_USER).where(APP_USER.ID.eq(user.id()))
                .fetchOne(APP_USER.EMAIL_VERIFIED_AT);
    }

    private static String subject() {
        return Ids.newId().toString();
    }
}

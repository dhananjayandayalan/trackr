package dev.trakr.api.identity.internal;

import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.identity.UserView;
import java.util.List;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * GitHub sign-in. GitHub speaks plain OAuth2, not OIDC: no id_token, and /user shows only a public
 * email, which may be missing or unverified. So we ask /user/emails for the primary address.
 */
@Service
class GitHubOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    record GitHubEmail(String email, boolean primary, boolean verified) {
    }

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final RestClient github = RestClient.create("https://api.github.com");
    private final IdentityService identityService;

    GitHubOAuth2UserService(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        // GET https://api.github.com/user; getName() is the numeric "id", GitHub's stable id for the person
        OAuth2User gitHubUser = delegate.loadUser(request);
        Optional<GitHubEmail> primary = primaryEmail(request.getAccessToken().getTokenValue());
        String name = gitHubUser.getAttribute("name");

        UserView user = identityService.linkOrCreateFromOAuth(
                request.getClientRegistration().getRegistrationId(),
                gitHubUser.getName(),
                primary.map(GitHubEmail::email).orElse(null),
                primary.map(GitHubEmail::verified).orElse(false),
                name != null ? name : gitHubUser.getAttribute("login"));
        return TrakrPrincipal.of(user);
    }

    private Optional<GitHubEmail> primaryEmail(String accessToken) {
        try {
            List<GitHubEmail> emails = github.get()
                    .uri("/user/emails")
                    .header("Accept", "application/vnd.github+json")
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GitHubEmail>>() {
                    });
            return emails == null ? Optional.empty() : emails.stream().filter(GitHubEmail::primary).findFirst();
        } catch (RestClientException e) {
            // an AuthenticationException sends the browser to the failure handler; anything else would be a 500
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response"),
                    "could not read the GitHub email addresses", e);
        }
    }
}

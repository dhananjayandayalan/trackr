package dev.trakr.api.shared.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    // BLOCK 1: password encoder — Argon2id, salted, parameters stored inside the hash string
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    // BLOCK 2: authentication manager — checks email + password for AuthController.login
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    // BLOCK 3: security context repository — AuthController saves the login here, i.e. into the session
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
            OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService) throws Exception {

        // BLOCK 4: the filter chain — authorization rules
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/auth/**", "/actuator/health/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll());

        // BLOCK 5: CSRF for an SPA — XSRF-TOKEN cookie readable by JS, echoed back in X-XSRF-TOKEN
        http.csrf(c -> c.spa());
        http.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        // BLOCK 6: OAuth2 login, only when registrations exist (the "oauth" profile, see application-oauth.yml)
        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        if (registrations != null) {
            http.oauth2Login(o -> o
                    .authorizationEndpoint(a -> a.authorizationRequestResolver(OAuthRedirects.rememberingNext(registrations)))
                    .userInfoEndpoint(u -> u.oidcUserService(oidcUserService).userService(oauth2UserService))
                    .successHandler(OAuthRedirects::onSuccess)
                    .failureHandler(OAuthRedirects::onFailure));
        }

        // BLOCK 7: JSON errors instead of redirects — 401/403 go through ApiExceptionHandler as problem+json
        http.exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) -> errors.resolveException(request, response, null, ex))
                .accessDeniedHandler((request, response, ex) -> errors.resolveException(request, response, null, ex)));
        // the default cache stores each rejected request in a new session, to replay after a login page;
        // an SPA does its own redirects, so that would only fill Redis with anonymous sessions
        http.requestCache(RequestCacheConfigurer::disable);

        // BLOCK 8: logout — invalidates the session (in Redis) and answers 204
        http.logout(l -> l
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));

        return http.build();
    }
}

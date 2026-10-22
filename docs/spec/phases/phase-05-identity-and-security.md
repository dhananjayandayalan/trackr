# Phase 05 · Identity and security

**Goal:** sign up, sign in (password and Google/GitHub), sign out, `GET /me`, CSRF protection and sessions stored in Redis.
**Spec:** 09-security §1–3, 03-api §3.1, 02-data-model (`app_user`, `user_identity`, `workspace*`, `password_reset`). **Time:** 3–4 days.

## Concepts you'll learn

- **The Security filter chain** — an ordered list of servlet filters; each request passes through it before reaching a controller.
- **`SecurityContext` / `Authentication`** — where "who is calling" lives, and how it's saved into the session.
- **Password hashing with Argon2id** — slow on purpose, salted, encoded with its parameters.
- **Session fixation** — why the session id changes at login.
- **OAuth2 Authorization Code flow / OIDC** — redirects, `state`, `code`, tokens, `id_token`, user info.
- **CSRF and the double-submit cookie** — why a cookie-authenticated SPA needs it.
- **Spring Session** — swaps the servlet container's in-memory sessions for Redis, transparently.

## Files in this phase

`dev.trakr.api.identity` (module root) · `.identity.internal.*` · `dev.trakr.api.workspace` · `dev.trakr.api.shared.security`

1. Catalog + build: Security starter, OAuth2 client starter, Data Redis starter, Spring Session Data Redis, Mail starter, `spring-security-test`, Testcontainers Redis (`com.redis:testcontainers-redis` or a `GenericContainer` with `@ServiceConnection(name = "redis")`)
2. `workspace/internal/Workspace.java`, `WorkspaceMember.java` (JPA entities), `WorkspaceRepository.java`
3. `workspace/WorkspaceService.java` (public API: `createPersonalWorkspace(user)`, `currentFor(userId)`)
4. `identity/internal/AppUser.java`, `UserIdentity.java`, `PasswordReset.java` (entities) + repositories
5. `identity/internal/PasswordPolicy.java`
6. `identity/IdentityService.java` (public: `signUp`, `findById`, `linkOrCreateFromOAuth`)
7. `identity/internal/TrakrUserDetailsService.java` + `TrakrPrincipal.java` (record implementing `UserDetails` and `OidcUser`/`OAuth2User` so both login types share one principal)
8. `identity/internal/OAuthUserService.java` (OIDC for Google) and `GitHubOAuth2UserService.java`
9. `shared/security/SecurityConfig.java`
10. `shared/security/SessionConfig.java`
11. `identity/web/AuthController.java` + request/response records (`SignUpRequest`, `LoginRequest`, `MeResponse`)
12. `identity/web/MeController.java`
13. `identity/internal/PasswordResetService.java` + email template (plain text)
14. Config: `application.yml` blocks for redis, session, oauth2 client registrations (secrets from env), mail (Mailpit)
15. Tests (below)

### Highlights — block plans for the files that teach the most

**`AppUser.java`**
| # | Cue | What |
|---|---|---|
| 1 | `// BLOCK 1: entity mapping` | `@Entity @Table(name = "app_user")`, `@Id UUID id`, columns from 02-data-model; protected no-arg constructor for JPA |
| 2 | `// BLOCK 2: factory` | `static AppUser register(String email, String displayName, String passwordHash, Clock clock)` → derives initials + picks an avatar colour deterministically from the id |
| 3 | `// BLOCK 3: behaviour` | `changePassword(hash)`, `markEmailVerified(clock)`; no public setters |
**Ask yourself:** why a static factory + no setters instead of a JavaBean?

**`SecurityConfig.java`**
| # | Cue | What |
|---|---|---|
| 1 | `// BLOCK 1: password encoder` | `@Bean PasswordEncoder` — Argon2 encoder from Spring Security's current recommended factory method |
| 2 | `// BLOCK 2: authentication manager` | `@Bean AuthenticationManager` from a `DaoAuthenticationProvider(userDetailsService)` with the encoder |
| 3 | `// BLOCK 3: security context repository` | `@Bean SecurityContextRepository` = `HttpSessionSecurityContextRepository` (the login controller saves into it) |
| 4 | `// BLOCK 4: the filter chain — authorization rules` | `http.authorizeHttpRequests(a -> a.requestMatchers("/api/v1/auth/**", "/actuator/health/**").permitAll().requestMatchers("/api/**").authenticated().anyRequest().permitAll())` |
| 5 | `// BLOCK 5: CSRF for an SPA` | `http.csrf(c -> c.spa())` if your Security 7 version has it, otherwise `CookieCsrfTokenRepository.withHttpOnlyFalse()` + `CsrfTokenRequestAttributeHandler` — check the reference docs and write down which you used |
| 6 | `// BLOCK 6: OAuth2 login` | `http.oauth2Login(o -> o.userInfoEndpoint(u -> u.oidcUserService(oidc).userService(github)).successHandler(spaRedirect).failureHandler(spaFailure))` |
| 7 | `// BLOCK 7: JSON errors instead of redirects` | `exceptionHandling(e -> e.authenticationEntryPoint(problemEntryPoint))` → 401 problem+json for `/api/**` |
| 8 | `// BLOCK 8: logout` | `logout(l -> l.logoutUrl("/api/v1/auth/logout").logoutSuccessHandler(204))` |
**Check:** `curl -i localhost:8080/api/v1/me` → 401 `application/problem+json`, not a 302 to a login page.

**`AuthController.login`**
| # | What |
|---|---|
| 1 | Build `UsernamePasswordAuthenticationToken.unauthenticated(email, password)` and call `authenticationManager.authenticate(...)` |
| 2 | Create an empty `SecurityContext`, set the authentication, `securityContextHolderStrategy.setContext(...)`, `securityContextRepository.saveContext(ctx, request, response)` |
| 3 | If `remember` → `request.getSession().setMaxInactiveInterval(30 days)` |
| 4 | Count failures in Redis (`INCR login:fail:{email}` with 15 min TTL); 10 → 429 |
**Ask yourself:** what would happen if you forgot step 2's `saveContext` (the classic Spring Security 6+ gotcha)?

**`OAuthUserService.java`** (Google, OIDC)
| # | What |
|---|---|
| 1 | Delegate to `OidcUserService` to load the user |
| 2 | Read `sub`, `email`, `email_verified`, `name` |
| 3 | `identityService.linkOrCreateFromOAuth("google", sub, email, verified, name)` — link by identity, else by verified email, else create user + personal workspace |
| 4 | Return a `TrakrPrincipal` that carries the user id |
GitHub is OAuth2 (no OIDC): call `https://api.github.com/user/emails` with the access token to find the primary verified email (use `RestClient`).

**`SessionConfig.java` + yml**
`spring.session.redis.namespace: trakr:session`, `server.servlet.session.cookie.name: TRAKR_SESSION`, `same-site: lax`, `http-only: true`, `secure: false` in dev / `true` in prod, `server.servlet.session.timeout: 12h`. Optional `@Bean CookieSerializer` if you need more control.
**Check:** after login, `redis-cli keys 'trakr:session*'` shows your session; restart the api and you're still logged in.

### OAuth client registration (do this once)

Create OAuth apps at Google Cloud Console and GitHub Developer settings with redirect URI `http://localhost:5173/login/oauth2/code/{google|github}` (the Vite proxy forwards it). Put ids/secrets in `infra/.env`; `application-dev.yml` reads `${GOOGLE_CLIENT_ID}` etc. Until then, the app must still start: guard the registrations with a profile or skip OAuth tests.

## Tests

- `PasswordPolicyTest` — table-driven (`@ParameterizedTest @CsvSource`).
- `IdentityServiceTest` — sign-up creates user + personal workspace + owner membership; duplicate email → `EmailTakenException`.
- `AuthFlowIT` (MockMvc, Testcontainers Postgres + Redis): signup → `GET /me` with session cookie → logout → `GET /me` 401. Mutations without CSRF header → 403 `CSRF_INVALID`.
- `OAuthLinkingTest` — unit test for link/create rules (mock nothing you own; use the real service with a test repository).
- Password reset: request → Mailpit received email (in IT use `GreenMail` or assert on a `JavaMailSender` test double) → reset → old sessions gone.

## Done when

- You can sign up and sign in with curl (remember the CSRF cookie dance) and with Google/GitHub in the browser once the frontend exists.
- Sessions survive an api restart.
- You can explain every block of the filter chain configuration.

Commit: `phase-05: identity, sessions, oauth, csrf`.

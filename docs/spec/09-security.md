# 09 · Security

## 1. Authentication

- **Sessions, not browser-held JWTs.** Spring Session Data Redis stores sessions; cookie `TRAKR_SESSION` (HttpOnly, SameSite=Lax, Secure outside dev, Path=/). Default idle timeout 12 h; "keep me signed in" sets a 30-day max-age.
- Both `api` and `collab` read the same Redis session namespace (`trakr:session`), so one login works for REST, SSE and WebSockets.
- **Password login:** `POST /auth/login` → a custom controller calls `AuthenticationManager` (DaoAuthenticationProvider with `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` or the current recommended factory), then saves the `SecurityContext` via `HttpSessionSecurityContextRepository`. Change the session id after login (session fixation protection is on by default — explain it).
- Password policy: ≥ 10 chars, one number, one symbol; check against a small embedded list of common passwords.
- Lockout: 10 failed logins per email per 15 min → 429 (Redis counter).
- **OAuth2/OIDC login:** `spring-boot-starter-oauth2-client` with registrations `google` (OIDC) and `github` (OAuth2 + `/user/emails`). A custom `OAuth2UserService` links by `user_identity(provider, subject)`, else by verified email, else creates a user + workspace. Success handler redirects to `/` (or the `next` param if it is a same-origin path).
- Password reset: random 32-byte token (base64url) emailed; store SHA-256 hash; 30-min expiry; single use; all sessions of the user are invalidated after reset (`FindByIndexNameSessionRepository`).

## 2. CSRF

- `CookieCsrfTokenRepository.withHttpOnlyFalse()` + `CsrfTokenRequestAttributeHandler` (SPA mode as documented for Spring Security 7). The SPA reads `XSRF-TOKEN` and sends `X-XSRF-TOKEN`.
- SSE GETs need no token. The collab WebSocket handshake is protected by the Origin check + SameSite cookie.

## 3. Authorization

- Spring Security only decides **authenticated or not** for `/api/**` (plus public endpoints: `/auth/**`, `/oauth2/**`, `/login/oauth2/**`, `/actuator/health`).
- Fine-grained rules live in services via a `ProjectAccess` component in the `project` module:
  `projectAccess.require(projectKey, Role.MEMBER)` → throws `NotFound` if the user cannot see the project (no existence leak) or `Forbidden` if they can see but not act.
- Method security (`@PreAuthorize("@projectAccess.can(#key, 'ADMIN')")`) is allowed for simple cases; explain proxies when you use it.
- Visibility: `private` → members only; `limited` → all workspace members can view; `open` → all workspace members can view and act as members.

## 4. Input and output safety

- Bean Validation on every request record; max lengths everywhere (summary 255, comment 20 kB, description HTML 200 kB).
- HTML from the editor and comments is sanitised server-side (OWASP Java HTML Sanitizer, allow-list in 03-api §3.5) before storage **and** the frontend never uses `dangerouslySetInnerHTML` except for sanitised server HTML in one `SafeHtml` component.
- TQL is parsed, never concatenated into queries; ES queries are built with the typed Java client.
- SQL only via JPA/jOOQ bind variables.
- Attachments: content type allow-list, size ≤ 25 MB, `Content-Disposition: attachment` on downloads except images/PDF; object keys never derived from user input except the sanitised file name.

## 5. Headers (set in api and by Caddy in prod)

`Content-Security-Policy: default-src 'self'; img-src 'self' data: blob:; connect-src 'self' wss:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self' https://accounts.google.com https://github.com` · `Strict-Transport-Security` (prod) · `X-Content-Type-Options: nosniff` · `Referrer-Policy: strict-origin-when-cross-origin` · `Permissions-Policy: camera=(), microphone=(), geolocation=()`.

## 6. Row-level security (defence in depth, phase 18)

- Policies from 02-data-model §7.
- Setting the tenant: a `TransactionSynchronization`-aware component runs `SELECT set_config('app.workspace_id', ?, true)` at the start of each transaction (true = local to the transaction, safe with pooled connections).
- The api connects as `trakr_api` which is **not** the table owner (owners bypass RLS unless `FORCE ROW LEVEL SECURITY`).
- Workers connect as `trakr_workers` with `BYPASSRLS` only if needed for cross-tenant projections — prefer setting the workspace from the event envelope.

## 7. Secrets

Local: `infra/.env` (git-ignored). Apps read env vars (`TRAKR_DB_PASSWORD`, `GOOGLE_CLIENT_ID`, …) via `application.yml` placeholders. Never commit OAuth secrets. Prod: environment variables injected by the platform.

## 8. Rate limiting

Redis token bucket (Lua script, atomic) keyed `rl:{userId}:{route-group}`; route groups: `write` 20/s burst 40, `search` 10/s burst 20, `auth` 5/min per IP.

## 9. Audit

`issue_event` is the audit trail for issue data. Project membership and workflow changes are not issue history; in phase 18 add a small `audit_log` table (actor, workspace, action, target, before/after jsonb, at) written by a module listener on `ProjectMemberChanged` and `WorkflowChanged`.

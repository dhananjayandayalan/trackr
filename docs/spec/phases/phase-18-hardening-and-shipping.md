# Phase 18 · Hardening and shipping

**Goal:** observable, secure, measured and packaged: tracing across all three apps and Kafka, row-level security, rate limits, security headers, end-to-end tests, performance budgets checked, hand-written Dockerfiles, Caddy in front, and a CI pipeline.
**Spec:** 00-product §8; 09-security §5–8; 10-testing; 11-infra §8. **Time:** 6–8 days.

## Concepts you'll learn

- **Distributed tracing** — trace ids flowing HTTP → DB → Kafka → workers → ES (W3C `traceparent`).
- **RED metrics** (rate, errors, duration) and **consumer lag**.
- **Row-level security** with transaction-local settings.
- **Token-bucket rate limiting** in Redis with a Lua script (atomicity).
- **Container images** — multi-stage builds, layered jars, non-root users, JVM ergonomics in containers, the Java 25 AOT cache.
- **Reverse proxies** — routing, compression, SSE flushing, WebSocket upgrade, TLS.
- **CI** — caching Gradle and pnpm, service containers, artifacts.

## 1. Observability

Files: catalog (`micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `micrometer-registry-prometheus`), `application.yml` blocks in all apps, `infra/docker-compose.yml` profile `observability` with Grafana's all-in-one OTel LGTM image (`grafana/otel-lgtm`) on 3000/4317/4318.
| # | What |
|---|---|
| 1 | `management.tracing.sampling.probability: 1.0` (dev), OTLP endpoint `http://localhost:4318/v1/traces` |
| 2 | Kafka observation on: `spring.kafka.template.observation-enabled: true`, `spring.kafka.listener.observation-enabled: true` → trace continues from api to workers |
| 3 | Log pattern includes `traceId`/`spanId` (`logging.pattern.correlation`) |
| 4 | Custom metrics: `trakr.issue.moves` counter (tags: project), `trakr.sse.subscribers` gauge, `trakr.collab.rooms` gauge |
**Check:** move a card; in Grafana find one trace spanning api `POST /move` → Kafka send → workers indexer → ES bulk.

## 2. Security hardening

1. `V014__row_level_security.sql` (V013 became phase 17's workflow version) — enable RLS + policies on tenant tables (wrap in `-- [jooq ignore start/stop]`); grants for `trakr_api`, `trakr_workers`, `trakr_collab` per 02-data-model §6.
2. `shared/security/TenantTransactionListener.java` — sets `app.workspace_id` with `set_config(..., true)` at transaction begin (hook via a custom `JpaTransactionManager` subclass's `doBegin` or a `TransactionSynchronization` + `DataSourceUtils` — research both, implement one, explain the choice).
3. Switch datasource credentials to the per-app roles; run the full IT suite → anything failing shows a missing grant or tenant setting.
4. `shared/web/RateLimitFilter.java` + `src/main/resources/redis/token_bucket.lua` (09-security §8).
5. `shared/web/SecurityHeadersFilter.java` (CSP etc. from 09-security §5) — or configure `http.headers(...)`; compare.
6. `audit_log` table + listener (09-security §9).
7. Delete `DiagnosticsController`; set `management.endpoints.web.exposure.include: health,info,prometheus` in prod.
   **Test:** `RlsIT` — with RLS on, a query run with workspace A's setting cannot see workspace B's issues even via a hand-written SQL statement.

## 3. Tests at the top of the pyramid

- `frontend/playwright.config.ts` — projects Chromium + WebKit, `webServer` entries for Vite preview; global setup signs in once and stores `storageState`.
- `e2e/*.spec.ts` — one spec per screen (12) + `live-board.spec.ts` + `collab.spec.ts`; each spec runs `@axe-core/playwright` and fails on violations.
- Load test (optional): `perf/k6/board.js` — 50 virtual users loading the board and moving cards; thresholds from 00-product §8.
- Lighthouse CI on `/p/MAR/board` with budgets: LCP < 1.5 s, total JS < 180 KB gzip.

## 4. Packaging

| File                                                           | Blocks                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| -------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `backend/apps/api/Dockerfile` (same shape for workers, collab) | 1 build stage `eclipse-temurin:25-jdk` running `./gradlew :apps:api:bootJar` with the Gradle cache mounted (`--mount=type=cache`) · 2 extract layers (`java -Djarmode=tools -jar api.jar extract --layers --destination extracted`) · 3 training run to create the AOT cache (`-XX:AOTCacheOutput=app.aot` with `-Dspring.context.exit=onRefresh` — check the current Spring Boot docs for the recommended flags) · 4 runtime stage `eclipse-temurin:25-jre`, non-root user, copy layers in dependency → snapshot → application order (explain layer caching), `ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-XX:MaxRAMPercentage=75", "-jar", "api.jar"]` |
| `frontend/Dockerfile`                                          | build with pnpm (cache mount) → copy `dist` into the Caddy image                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `infra/caddy/Caddyfile`                                        | site block · `encode zstd gzip` · `handle /api/*` → `reverse_proxy api:8080 { flush_interval -1 }` (SSE) · `handle /collab/*` → `reverse_proxy collab:8081` (WebSocket upgrade is automatic) · `handle /oauth2/* /login/oauth2/*` → api · static files with `Cache-Control: public, max-age=31536000, immutable` for `/assets/*` and `no-cache` for `index.html` · security headers                                                                                                                                                                                                                                                                        |
| `infra/docker-compose.prod.yml`                                | the three apps + Caddy + the infra services, healthchecks, `depends_on: condition: service_healthy`, per-app env                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |

**Check:** `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --wait` → open `http://localhost` → full app works through Caddy; api container starts in < 1 s with the AOT cache (compare with and without).

## 5. CI — `.github/workflows/ci.yml` (written by hand)

| #   | Job/step                 | What                                                                                                                                                                                               |
| --- | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | triggers                 | `push` to main, `pull_request`                                                                                                                                                                     |
| 2   | `backend` job            | checkout · `actions/setup-java` (Temurin 25) · `gradle/actions/setup-gradle` (caching) · `./gradlew check` (Testcontainers works on GitHub-hosted Ubuntu runners) · upload test reports on failure |
| 3   | `frontend` job           | checkout · `pnpm/action-setup` · `actions/setup-node` (24, cache pnpm) · `pnpm install --frozen-lockfile` · `pnpm typecheck && pnpm lint && pnpm test && pnpm build` · bundle-size check           |
| 4   | `e2e` job (needs both)   | compose up infra · start apps from built jars · `pnpm exec playwright install --with-deps` · `pnpm e2e` · upload the Playwright report                                                             |
| 5   | `images` job (main only) | build the four images (no push until you have a registry)                                                                                                                                          |

## Done when

- One trace shows the whole move → index journey.
- RLS is on and every test still passes using per-app roles.
- E2E + a11y suites are green in CI.
- The prod-shaped stack runs behind Caddy on `http://localhost`.
- Your learning log has an entry for every block of every phase — you built a Jira clone and know why every line is there.

Commit: `phase-18: hardening and shipping`.

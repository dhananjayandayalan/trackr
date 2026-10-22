# 10 · Testing

Tests are written **in the same phase** as the code, block by block like everything else.

## 1. Pyramid

| Level         | Tool                                                                                                | What                                                                                             | Where           |
| ------------- | --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ | --------------- |
| Unit (pure)   | JUnit 5 + AssertJ                                                                                   | Domain rules: `Rank`, `Ids`, issue transitions, TQL → ES translation, lib0 codec                 | `src/test/java` |
| Module        | Spring Modulith `@ApplicationModuleTest`                                                            | One module in isolation, events published (`PublishedEvents`/`Scenario`)                         | api             |
| Architecture  | `ApplicationModules.verify()` + `Documenter`                                                        | No illegal module dependencies; generates C4/PlantUML diagrams into `build/spring-modulith-docs` | api             |
| Slice         | `@WebMvcTest`, `@DataJpaTest`, `@JooqTest`                                                          | Controllers (problem details, validation), repositories, queries                                 | api             |
| Integration   | `@SpringBootTest` + Testcontainers (`@ServiceConnection`) for Postgres, Redis, Kafka, Elasticsearch | Full flows: create issue → event in Kafka → indexed in ES                                        | all apps        |
| Contract      | OpenAPI snapshot test                                                                               | `/v3/api-docs` diff fails the build unless the snapshot is updated on purpose                    | api             |
| Frontend unit | Vitest                                                                                              | `rank.ts`, `applyMove`, cache patchers, SSE event parsing                                        | `*.test.ts`     |
| Component     | Vitest + Testing Library + `vitest-browser` or jsdom                                                | UI components' behaviour and a11y roles                                                          | `*.test.tsx`    |
| API mocking   | MSW                                                                                                 | Component/page tests without the backend                                                         | `src/test/msw/` |
| End-to-end    | Playwright (Chromium + WebKit)                                                                      | The 12 screens' main flows, incl. two-browser live update and collaborative typing               | `frontend/e2e`  |
| Accessibility | `@axe-core/playwright`                                                                              | No violations per screen                                                                         | e2e             |
| Performance   | k6 (optional) + Lighthouse CI                                                                       | 00-product §8 budgets                                                                            | phase 18        |

## 2. Rules

- Name tests by behaviour: `movesIssueToDoneAndSetsResolvedAt`.
- Testcontainers are reused across tests (`@ServiceConnection` + a shared `TestcontainersConfiguration` class; enable reuse locally with `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`).
- Never mock what you own in integration tests; mock only external HTTP (OAuth providers) with WireMock.
- Shared fixtures: `docs/spec/fixtures/rank-vectors.json`, `apps/collab/src/test/resources/fixtures/*.bin` (Yjs messages produced by a one-off Node script, checked in).
- Each phase lists its required tests under "Tests" — the phase is not done until they pass.

## 3. Commands

| Command                     | Runs                                                      |
| --------------------------- | --------------------------------------------------------- |
| `./gradlew test`            | backend unit + slice + module tests                       |
| `./gradlew integrationTest` | Testcontainers suites (separate source set, see phase 04) |
| `./gradlew check`           | everything + Modulith verify + OpenAPI snapshot           |
| `pnpm test`                 | Vitest                                                    |
| `pnpm e2e`                  | Playwright against `docker compose` + running apps        |

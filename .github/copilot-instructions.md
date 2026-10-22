# TRAKR — Copilot instructions (learning mode)

TRAKR is a Jira clone built **from an empty folder, by hand, to learn how every file and every block works**.
The developer types the code. You (Copilot) assist one block at a time and explain it. Speed matters less than understanding.

The spec is the source of truth: `docs/spec/`. Start at `docs/spec/README.md`. The current phase lives in `docs/spec/phases/`.

---

## 1. The learning contract — follow this on every request

1. **One block at a time. Never a whole file.**
   A *block* is one logical unit, roughly 3–30 lines:
   - Java: an import group, a field group, one constructor, one method, one annotation set, one `@Bean`.
   - TypeScript/React: one hook, one handler, one JSX region, one type, one function.
   - CSS: one rule or one tightly related group of rules (for example a component's states).
   - Config (YAML, Gradle, Compose, JSON): one section or one top-level key.
   - SQL: one `CREATE TABLE`, one index group, one policy.
2. **After every block, explain it** in this shape (keep it short):
   - **What** — what the block does, line by line if it is dense.
   - **Why** — why it exists and what would break without it.
   - **Concept** — the one underlying idea to remember (for example "auto-configuration", "optimistic locking", "cascade layers").
   - **Watch out** — one common mistake or gotcha.
3. **Then stop.** Do not continue to the next block unless asked. Offer the name of the next block from the phase file.
4. **Never run scaffolding generators** or suggest them: no Spring Initializr, no `create-vite`, no `npm create`, no JHipster, no Yeoman, no copy-pasted starter repositories. Every config file is written by hand from the spec.
5. **Follow the spec exactly.** Table names, column names, endpoint paths, Kafka topics, event types, CSS tokens and file paths come from `docs/spec/`. If the spec does not cover something, say so and ask — do not invent it.
6. **When the developer pastes code and asks "explain"**, walk through it block by block in the same What/Why/Concept/Watch-out shape.
7. **When fixing a bug**, explain the cause first, then show only the lines that change.
8. **Prefer the platform.** Use what the JDK, Spring, the browser and CSS already provide before adding a library. Every new dependency must be listed in the spec; if it is not, ask.

## 2. Stack (pinned majors — see `docs/spec/01-architecture.md`)

| Area | Choice |
|---|---|
| Language/runtime | Java 25 (LTS), virtual threads on |
| Backend framework | Spring Boot 4.1.x, Spring Modulith 2.x, Spring Security 7 |
| Persistence | PostgreSQL 18, Flyway, Spring Data JPA (writes), jOOQ (reads) |
| Messaging | Apache Kafka 4.x (KRaft, no ZooKeeper), Spring for Apache Kafka |
| Search | Elasticsearch 9.x via `co.elastic.clients:elasticsearch-java` |
| Cache/sessions | Redis 8, Spring Session Data Redis |
| Object storage | SeaweedFS (S3 API) via AWS SDK for Java v2 |
| Collaboration | Yjs CRDT; Java WebSocket relay in `apps/collab` |
| Build | Gradle (Kotlin DSL), version catalog, convention plugins in `build-logic` |
| Frontend | React 19 + TypeScript (strict), React Compiler 1.0, Vite 8, pnpm |
| Frontend data | TanStack Router (code-based routes), TanStack Query, openapi-fetch + openapi-typescript |
| Styling | Plain modern CSS: cascade layers, custom properties, nesting, CSS Modules, container queries, Popover API, anchor positioning |
| Editor | Tiptap 3 + Yjs |
| Tests | JUnit 5, Testcontainers, Spring Modulith verification; Vitest, Testing Library, MSW, Playwright |

### Spring Boot 4 gotchas — your training data is mostly Boot 3, so check these
- Jackson 3 is the default: packages are `tools.jackson.*`, not `com.fasterxml.jackson.databind.*` (annotations stay in `com.fasterxml.jackson.annotation`).
- Starters are more modular (for example the Web MVC starter is `spring-boot-starter-webmvc`). Use the names in `backend/gradle/libs.versions.toml`.
- Jakarta namespaces only (`jakarta.persistence`, `jakarta.servlet`). Never `javax.*`.
- Null-safety uses JSpecify annotations (`org.jspecify.annotations.Nullable`).
- If you are unsure whether an API exists in Boot 4.1, say so instead of guessing.

## 3. Conventions

- Base Java package: `dev.trakr`. API modules live in `dev.trakr.api.<module>` (see `docs/spec/01-architecture.md` §4).
- IDs are UUIDv7 generated in the application (`dev.trakr.kernel.Ids`).
- Time is `java.time.Instant` in UTC; dates without time are `LocalDate`.
- REST errors use RFC 9457 `application/problem+json` with a `code` extension (see `docs/spec/03-api.md`).
- Kafka events use the envelope in `docs/spec/04-events.md`. Never publish to Kafka directly from a controller.
- Frontend files: components `PascalCase.tsx` with a sibling `PascalCase.module.css`; hooks `useThing.ts`; everything else `kebab-case.ts`.
- CSS: only tokens from `frontend/src/styles/tokens.css` for colour, space, radius, shadow and type. No hard-coded hex values inside components.
- Tests sit next to the code they test (`*Test.java` in `src/test/java` with the same package, `*.test.ts(x)` beside the file).
- Commit messages: `phase-NN: <what you learned/built>`.

## 4. Explaining style

- Plain English. Short sentences. Define a term the first time it appears.
- Link the block back to the spec section it implements (for example "implements 04-events §3, the outbox").
- When there are two reasonable ways, name both in one line each and say why the spec picked one.

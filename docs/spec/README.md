# TRAKR spec — start here

TRAKR is a Jira clone (boards, backlog, sprints, issues, search, timeline, reports, live collaboration) built from an empty folder to **learn what every file and every block of code does**.

This folder is the spec. It is written so GitHub Copilot can help you **one block at a time** while you stay the author.

The application is complete: all 19 phases (00–18) are built. The per-phase build plans (`phases/`) were removed
after phase 18; §4 below keeps the map of what each phase built, and the code's `BLOCK` comments still follow it.

## 1. How the spec is organised

| File | What it answers |
|---|---|
| [00-product.md](00-product.md) | What we are building: features, screens, rules, glossary, non-goals |
| [01-architecture.md](01-architecture.md) | The three apps, the modules, how data flows, consistency model, versions |
| [02-data-model.md](02-data-model.md) | Every table, column, constraint and index, and why |
| [03-api.md](03-api.md) | REST conventions, every endpoint, errors, concurrency, SSE stream |
| [04-events.md](04-events.md) | Domain events, outbox, Kafka topics, envelope, consumers, retries |
| [05-search.md](05-search.md) | Elasticsearch index, analyzers, TQL query language, reindexing |
| [06-collab.md](06-collab.md) | Collaborative description editing: Yjs, the sync protocol, the Java relay |
| [07-frontend.md](07-frontend.md) | React app structure, routing, data layer, optimistic updates, drag and drop |
| [08-css-volt.md](08-css-volt.md) | The VOLT design system in pure CSS: layers, tokens, components |
| [09-security.md](09-security.md) | Sessions, login, OAuth, CSRF, roles, row-level security |
| [10-testing.md](10-testing.md) | What to test at each level and how |
| [11-infra.md](11-infra.md) | Docker Compose services, ports, credentials, env vars, local URLs |

The reference files (00–11) say **what** and **why**.

## 2. The learning loop (do this for every block)

The phase plans split every file into numbered blocks like this (the `BLOCK n` comments in the code are what is left of them):

> **Block 3 — Kafka listener (KRaft) config**
> *Cue:* `# BLOCK 3: kafka listeners — one for containers, one for the host`
> *What it must do:* …
> *Check:* …
> *Ask yourself:* …

1. **Read** the purpose of the block in the reference file it implements. Close the spec if you can — try to predict the code.
2. **Type the cue comment** into the file. Copilot inline completion uses the comment, the open file and your open tabs to suggest the block.
3. **Accept line by line**, not all at once (`Ctrl+→` / `Cmd+→` accepts one word; `Tab` accepts the whole suggestion). Read each line before you accept it. Reject anything the spec does not ask for.
4. **Explain it back** in your own words. If you can't, select the block and ask Copilot Chat: *"Explain this block"* — the repo's Copilot instructions make it answer as What / Why / Concept / Watch out.
5. **Run the Check** (a test, a curl, a page in the browser).
6. **Answer "Ask yourself"** in your learning log (`docs/learning-log.md`, one line per block is enough).
7. Delete the cue comment or turn it into a real comment if it still helps.

### Keeping Copilot to block size
- Inline completion does **not** read `.github/copilot-instructions.md`; it reads your code, your comment and neighbouring tabs. The cue comment is how you steer it — that is why every block has one.
- Keep **the reference file you are implementing open in a tab** next to the code. Copilot uses open tabs as context, so suggestions follow the spec's names.
- Turn off multi-line "next edit" style suggestions if your editor has them, so Copilot does not rewrite code you did not ask about. Keep plain inline completion on.
- Copilot **Chat** and **agent mode** do read `.github/copilot-instructions.md` and the path-scoped files in `.github/instructions/`. They are told never to write whole files and to explain after every block. Don't use agent mode to "do the phase" — that defeats the purpose.
- When Copilot suggests a Spring Boot 3 or React 18 pattern, that is a learning moment: check the spec, fix it, note it in the log.

## 3. Rules of the build

- **No generators, no boilerplate repos.** Every `build.gradle.kts`, `package.json`, `tsconfig.json`, `vite.config.ts`, `application.yml`, `docker-compose.yml` is typed block by block.
  The only generated files: Gradle wrapper, pnpm lockfile, jOOQ classes, `schema.d.ts`.
- **One change at a time.** Each change ends green: its checks pass and you can explain every block.
- **Tests come with the code**, in the same change.
- **Commit at the end of each block group** with a message that says what changed.

## 4. Phase map (what was built, in order)

| # | Phase | You will learn |
|---|---|---|
| 00 | Workstation and repository | Toolchain, repo layout, git hygiene, Copilot setup |
| 01 | Local infrastructure | Docker Compose, Postgres, Redis, Kafka KRaft, Elasticsearch, SeaweedFS, Mailpit |
| 02 | Gradle from scratch | Settings, version catalog, convention plugins, multi-project builds |
| 03 | First Spring Boot app | Auto-configuration, profiles, Actuator, virtual threads |
| 04 | Database layer | Flyway, Hikari, JPA, jOOQ codegen, Testcontainers |
| 05 | Identity and security | Sessions in Redis, password hashing, OAuth2 login, CSRF |
| 06 | Workspaces and projects | Spring Modulith modules, REST, problem details, OpenAPI |
| 07 | Issues and workflow | Aggregates, optimistic locking, issue keys, history |
| 08 | Boards, sprints, backlog | Fractional ranking, jOOQ projections, sprint lifecycle |
| 09 | Events: outbox → Kafka → SSE | Domain events, outbox, Kafka producers/consumers, Server-Sent Events |
| 10 | Workers app | Idempotent consumers, Elasticsearch indexing, notifications, report projections |
| 11 | Search and TQL | ANTLR grammar, query compilation to Elasticsearch, saved filters |
| 12 | Frontend foundation | Vite, TypeScript, React Compiler, CSS layers and tokens, app shell |
| 13 | Frontend data layer | OpenAPI types, TanStack Query + Router, auth flow, first screens |
| 14 | Board and backlog UI | Drag and drop, optimistic moves, live updates, virtualisation |
| 15 | Issue detail and create issue | Dialogs, forms with actions, comments, uploads to S3 |
| 16 | Collaborative editing | WebSockets in Spring, Yjs sync protocol, Tiptap |
| 17 | Search, timeline, reports, settings | Query UI, charts in SVG, admin screens |
| 18 | Hardening and shipping | Observability, performance budgets, E2E tests, containers, reverse proxy |

## 5. Learning log template

Create `docs/learning-log.md`:

```md
## phase-03 · block 4 · application.yml — virtual threads
- In my words: …
- Surprised me: …
- Question for later: …
```

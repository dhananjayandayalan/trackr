# Phase 06 · Workspaces and projects (Spring Modulith, REST, problem details, OpenAPI)

**Goal:** create, list, view, update and archive projects; templates seed a workflow and a board; module boundaries are verified by a test; errors are RFC 9457; the OpenAPI document is generated.
**Spec:** 01-architecture §4, 02-data-model (project, workflow, board tables), 03-api §1–2, §3.2–3.3. **Time:** 3 days.

## Concepts you'll learn

- **Application modules** — a package is a module; `internal` sub-packages are hidden; `@ApplicationModule(allowedDependencies = …)` documents intent.
- **Synchronous vs asynchronous event listeners** — `@EventListener` (same transaction, same thread) vs `@ApplicationModuleListener` (after commit, new transaction, async).
- **Problem details** — one error format for every failure.
- **ETags and conditional requests** — `If-Match` → 412.
- **Idempotency keys** — safe retries of POSTs.
- **OpenAPI generation** — the contract is derived from code, then becomes the frontend's types.

## Files in this phase

1. Catalog/build: `spring-modulith-starter-core`, `spring-modulith-starter-jpa`, `spring-modulith-starter-test`, springdoc `springdoc-openapi-starter-webmvc-ui` (the line for Boot 4)
2. Migrations `V003__projects.sql`, `V004__workflow.sql`, `V005__boards.sql`
3. `package-info.java` for every module created so far (`identity`, `workspace`, `project`, `workflow`, `board`, `shared`)
4. `project/internal/Project.java`, `ProjectMember.java`, `ProjectStar.java`, `ProjectVisit.java` + repositories
5. `project/Template.java` (enum), `project/ProjectCreated.java` (event record) and other `project` events
6. `project/ProjectService.java` (public API) + `project/ProjectAccess.java` (authorization helper)
7. `project/internal/KeySuggester.java`
8. `project/internal/ProjectQueries.java` (jOOQ list with counts)
9. `project/web/ProjectController.java`, `ProjectMemberController.java` + records
10. `workflow/internal/*` entities + `workflow/WorkflowService.java` + `workflow/internal/TemplateSeeder.java` (listens to `ProjectCreated`)
11. `board/internal/*` entities + `board/BoardService.java` + seeding listener
12. `shared/web/ProblemDetailsAdvice.java`, `shared/web/TrakrException.java` (+ subclasses `NotFound`, `Conflict`, `VersionMismatch`, `Forbidden`)
13. `shared/web/ETags.java`
14. `shared/web/IdempotencyFilter.java` (Redis, 24 h)
15. `shared/config/OpenApiConfig.java`
16. Tests: `ModularityTests`, `ProjectServiceTest` (`@ApplicationModuleTest`), `ProjectApiIT`, `ProblemDetailsTest`

### `package-info.java` (one per module)

| #   | Cue                              | What                                                                                                                                                                    |
| --- | -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: module declaration` | `@org.springframework.modulith.ApplicationModule(displayName = "Projects", allowedDependencies = { "workspace", "identity", "shared" }) package dev.trakr.api.project;` |

**Ask yourself:** why does `workflow` depend on `project` (it listens to `ProjectCreated`) but `project` must **not** depend on `workflow`?

### `ProjectService.create`

| #   | Cue                                | What                                                                                                                                                                                              |
| --- | ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: authorize + validate` | caller must be a workspace member; key matches `^[A-Z]{2,6}$`; key unique (catch the unique-constraint violation and translate to `PROJECT_KEY_TAKEN` — explain why checking first is not enough) |
| 2   | `// BLOCK 2: create aggregate`     | `Project.create(...)`, add the creator as `admin` member, save                                                                                                                                    |
| 3   | `// BLOCK 3: publish`              | `events.publishEvent(new ProjectCreated(id, workspaceId, key, template, …))`                                                                                                                      |
| 4   | `// BLOCK 4: invites`              | existing users → members; unknown emails → `ProjectInviteRequested` event (emails later via workers)                                                                                              |

The method is `@Transactional`. The workflow and board seeders use **`@EventListener`** (synchronous) so seeding is part of the same transaction — if seeding fails, no half-created project exists.

### `TemplateSeeder.on(ProjectCreated)` (workflow module)

Table-driven from 00-product §5: a `Map<Template, List<StatusSeed>>`; insert statuses with positions and categories; insert transitions "every status → every other status"; board columns are created by the board module's own listener from the statuses (it calls `workflowService.statusesOf(projectId)` — allowed dependency).

### `ProjectQueries.listVisible(userId, filters)` — your first real jOOQ query

| #   | What                                                                                                                                                                                                                                                                  |
| --- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `select` project fields + `field(selectCount().from(ISSUE).where(ISSUE.PROJECT_ID.eq(PROJECT.ID).and(ISSUE.STATUS_CATEGORY.ne("done")).and(ISSUE.DELETED_AT.isNull())))` as `openIssues` (issue table arrives in phase 07 — start without this field and add it then) |
| 2   | visibility condition: member OR access in (`limited`,`open`) for workspace members                                                                                                                                                                                    |
| 3   | optional filters built as a `List<Condition>` and combined with `DSL.and(conditions)`                                                                                                                                                                                 |
| 4   | `.fetch(Records.mapping(ProjectSummary::new))` → records straight to JSON                                                                                                                                                                                             |

### `ProblemDetailsAdvice`

| #   | Cue                                  | What                                                                                                                                                                                      |
| --- | ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: extend the Spring base` | `@RestControllerAdvice class ProblemDetailsAdvice extends ResponseEntityExceptionHandler` — gives you problem details for Spring's own exceptions                                         |
| 2   | `// BLOCK 2: our exceptions`         | `@ExceptionHandler(TrakrException.class)` → `ProblemDetail.forStatusAndDetail(...)`, set `type` (`https://trakr.dev/problems/<kebab-code>`), `title`, property `code`, property `traceId` |
| 3   | `// BLOCK 3: validation`             | override `handleMethodArgumentNotValid` → `VALIDATION_FAILED` + `errors[]`                                                                                                                |
| 4   | `// BLOCK 4: optimistic locking`     | `ObjectOptimisticLockingFailureException` and our `VersionMismatch` → 412 with `current`                                                                                                  |
| 5   | `// BLOCK 5: unique violations`      | `DataIntegrityViolationException` → map known constraint names (`uq_project_workspace_key` → `PROJECT_KEY_TAKEN`), else 409 generic                                                       |

### `ETags` + PATCH

`ETags.of(version)` → `"v12"`; `ETags.requireMatch(ifMatchHeader, currentVersion)` throws `VersionMismatch`. `PATCH /projects/{key}` reads `If-Match`, applies the merge patch to a `ProjectPatch` record (explain how you distinguish "absent" from `null` — e.g. bind to `JsonNode`/`Map` first, or use `Optional` fields with a custom deserializer; pick one and justify).

### `IdempotencyFilter`

`OncePerRequestFilter` for POST with `Idempotency-Key`: Redis `SET idem:{userId}:{key} "pending" NX EX 86400`; if it existed → return the stored response (or 409 if still pending); after the controller → store status + body. Teach: why `NX`, and why the key is scoped by user.

## Tests

- `ModularityTests`: `ApplicationModules.of(ApiApplication.class).verify()` and `new Documenter(modules).writeDocumentation()` — open the generated diagrams.
- `ProjectServiceTest` with `@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)` and `Scenario`: `scenario.stimulate(() -> service.create(...)).andWaitForEventOfType(ProjectCreated.class).toArriveAndVerify(e -> …)`.
- `ProjectApiIT`: create (each template) → `GET /projects/{key}` shows seeded statuses/columns; duplicate key → 409 `PROJECT_KEY_TAKEN`; stale `If-Match` → 412; viewer cannot PATCH → 403; non-member of private project → 404.
- `ProblemDetailsTest` (`@WebMvcTest`): every error has `type`, `title`, `status`, `code`.

## Explore

Open `/swagger-ui.html`; read the generated schema for `ProjectSummary`. Add `@Operation(operationId=…)` everywhere — the frontend's function names depend on it.

## Done when

- `./gradlew check` green including `ModularityTests`.
- You can explain the difference between `@EventListener` and `@ApplicationModuleListener` with the seeding example.

Commit: `phase-06: projects, modules, problem details, openapi`.

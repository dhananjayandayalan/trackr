# Phase 07 · Issues and workflow

**Goal:** create, read, edit, transition and delete issues with correct keys, workflow rules, optimistic locking and a full history trail. Implement fractional ranking.
**Spec:** 00-product §4, 02-data-model (`issue`, `issue_event`, `issue_link`, `watcher`, `worklog`, `issue_view`, `issue_star`), 03-api §3.4. **Time:** 4 days.

## Concepts you'll learn

- **Aggregate** — a cluster of objects changed together through one root (`Issue`), with invariants enforced in its methods.
- **Optimistic locking** — `@Version`; the losing writer gets an exception instead of silently overwriting.
- **Row locks for counters** — `UPDATE … RETURNING` to allocate the next issue number without races.
- **Fractional indexing** — strings that always have room between them.
- **History as data** — append-only events per change.
- **jOOQ `multiset`** — nested collections in one SQL query.

## Files in this phase

1. `libs/kernel/…/Rank.java` + `RankTest.java` + `docs/spec/fixtures/rank-vectors.json`
2. Migrations `V006__sprints.sql` (table only — logic in phase 08), `V007__issues.sql`, `V008__issue_children.sql`
3. `issue/package-info.java` (depends on project, workflow, identity, shared)
4. `issue/IssueType.java`, `Priority.java` (enums with `@JsonValue` lowercase)
5. `issue/internal/Issue.java` (entity + behaviour)
6. `issue/internal/IssueEventRecord.java` (history entity) + `IssueHistory.java` (diff → rows)
7. `issue/internal/IssueNumberAllocator.java`
8. `issue/internal/IssueRepository.java`, `issue/internal/IssueQueries.java`
9. `issue/IssueService.java` (+ command records `CreateIssue`, `PatchIssue`, `TransitionIssue`)
10. `issue/events/*.java` — or better: payload records in `libs/contracts` (`IssueCreated`, `IssueUpdated`, `IssueTransitioned`, `IssueDeleted`, `IssueLinked`, `WorkLogged`) + `IssueSnapshot`
11. `workflow/WorkflowService.java` — add `requireTransition(projectId, fromStatusId, toStatusId)` and `status(id)`
12. `issue/web/IssueController.java`, `IssueLinkController.java`, `WatchController.java`, `WorklogController.java` + records
13. Tests

### File 1 — `Rank.java` (fractional indexing)

Digits: `0-9A-Za-z` (62 symbols, ASCII order = sort order under `COLLATE "C"`).
| # | Cue | What |
|---|---|---|
| 1 | `// BLOCK 1: alphabet and helpers` | `DIGITS` string, `digitValue(char)`, `MIDPOINT` behaviour |
| 2 | `// BLOCK 2: midpoint(a, b)` | Returns a string strictly between `a` and `b` (`b` may be null = +∞): walk digit by digit; if digits differ by > 1 take the middle digit; else append and recurse. Never return a string ending in `'0'` (it would have no room below it) |
| 3 | `// BLOCK 3: between(before, after)` | Public API: `between(null, null)` → `"V"` (the middle digit; `"a0"` from fractional-indexing libraries ends in `'0'`, which BLOCK 2 forbids); `between(x, null)` → after x; `between(null, y)` → before y; both → midpoint. Throw if `before >= after` |
| 4 | `// BLOCK 4: nBetween(before, after, n)` | Evenly spread n keys (used when inserting many issues at once, e.g. sprint completion) |
**Tests:** property-style loop: insert 10 000 times at random positions and assert the list stays sorted and unique; insert 1 000 times always at the front and check the length stays reasonable (< 200 chars). Generate `rank-vectors.json` (inputs → expected outputs) from the Java tests; the TypeScript `rank.ts` (phase 14) must produce the same outputs.
**Ask yourself:** why does `COLLATE "C"` matter for this column? (Try `ORDER BY` with the default collation on `"a"`, `"B"`.)

### Migration `V007__issues.sql`

Blocks: 1 table with every column from 02-data-model · 2 CHECK constraints (type, priority, status_category, subtask-needs-parent, epic-has-no-parent, points range) · 3 unique constraints (project+number, workspace+key) · 4 the five indexes, **each with a comment naming the query it serves**.

### File 5 — `Issue.java`

| #   | Cue                                | What                                                                                                                                                  |
| --- | ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: mapping`              | columns; `@Version long version`; `@JdbcTypeCode(SqlTypes.ARRAY) List<String> labels`; `@JdbcTypeCode(SqlTypes.JSON) Map<String,Object> customFields` |
| 2   | `// BLOCK 2: factory`              | `Issue.create(cmd, number, key, initialStatus, rank, reporterId, clock)` — validates parent rules                                                     |
| 3   | `// BLOCK 3: transitionTo(status)` | sets `statusId`, `statusCategory`; sets/clears `resolvedAt` when entering/leaving `done`                                                              |
| 4   | `// BLOCK 4: apply(PatchIssue)`    | applies only present fields; returns a `List<FieldChange>` (field, old, new) used for history and the `IssueUpdated` event                            |
| 5   | `// BLOCK 5: softDelete(clock)`    |                                                                                                                                                       |
| 6   | `// BLOCK 6: snapshot()`           | builds the `IssueSnapshot` for events                                                                                                                 |

**Watch out:** Hibernate only increments `version` if the entity is dirty — a transition that changes nothing must not publish an event.

### File 7 — `IssueNumberAllocator.java`

`dsl.update(PROJECT).set(PROJECT.ISSUE_SEQ, PROJECT.ISSUE_SEQ.plus(1)).where(PROJECT.ID.eq(id)).returning(PROJECT.ISSUE_SEQ).fetchOne()` — runs inside the same transaction as the insert.
**Ask yourself:** two users create MAR issues at the same moment — walk through what each transaction sees (row lock on the project row until commit). What does this cost under heavy concurrent creation, and why is it fine for us?

### File 9 — `IssueService`

| Method                             | Blocks                                                                                                                                                                                                                                              |
| ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `create`                           | access check (member) → allocate number → resolve initial status (first `todo` by position) → rank = `Rank.between(lastRankInBacklogOrSprint, null)` → save → history `created` → auto-watch reporter + assignee → publish `IssueCreated(snapshot)` |
| `get`                              | access check (viewer) → `IssueQueries.detail(key)` (one query with `multiset` for subtasks, links, watchers count, attachments) → upsert `issue_view` (async listener is fine — explain the trade-off)                                              |
| `patch`                            | `If-Match` check → `issue.apply(patch)` → history rows → `IssueUpdated(changes, snapshot)`                                                                                                                                                          |
| `transition`                       | `workflow.requireTransition` → `issue.transitionTo` → history → `IssueTransitioned`                                                                                                                                                                 |
| `delete`                           | soft delete issue and subtasks → `IssueDeleted`                                                                                                                                                                                                     |
| `link`, `watch`, `logWork`, `star` | small, each with its event/history row                                                                                                                                                                                                              |

All events are records from `libs/contracts`, implementing `TrakrEvent` (04-events §3) with `sequence = issue.getVersion()` **after flush** — explain why you need `entityManager.flush()` (or compute `version + 1`) to know the new version.

### File 8 — `IssueQueries.detail`

Use `multiset(select(...).from(ISSUE.as("sub")).where(...)).as("subtasks").convertFrom(r -> r.map(Records.mapping(SubtaskRow::new)))`. One round trip for the whole issue page.

## Tests

- `RankTest` (above), `IssueTest` (pure: transitions set/clear `resolvedAt`; subtask without parent rejected; patch returns exact changes).
- `IssueServiceIT` (`@ApplicationModuleTest(ALL_DEPENDENCIES)`, in `integrationTest` because it needs Postgres; decided: sequence 0 for `IssueCreated`, since a new row is version 0): create publishes `IssueCreated` with sequence 0/1 (decide and document); transition not allowed → `TransitionNotAllowed`.
- `IssueNumberConcurrencyIT`: 50 virtual threads create issues in one project concurrently → numbers 1..50 with no gaps or duplicates.
- `IssueApiIT`: full CRUD via MockMvc incl. 412 on stale `If-Match`, 404 for invisible projects, history endpoint lists changes in order.

## Decisions taken while building it

- Event payloads live in `libs/contracts` (`dev.trakr.contracts.events`), with `TrakrEvent` sealed. The envelope (`EventEnvelope`) waits for phase 09.
- `between(x, null)` / `between(null, y)` step one digit instead of halving the gap: appending or prepending 1 000 times stays ~35 characters.
- `project.issue_seq` is `insertable = false, updatable = false` in `Project`: Hibernate's UPDATE lists every column and would write back a stale value.
- The rank of a new issue is read after the number is allocated (the project row lock), so concurrent creates get distinct ranks.
- Linking bumps the source issue's version (the ETag covers links, and `IssueLinked` gets a fresh sequence). Unlinking publishes `IssueUpdated` with a `links` change, because the catalogue has no unlinked type.
- `issue_view` is upserted synchronously in `get` (one `ON CONFLICT` statement); watchers, stars and views use jOOQ upserts so repeated PUTs never hit the primary key.
- History is newest first, paged as `{items, nextCursor}` (03-api §1), cursor = last `issue_event.id`.

## Done when

- You can create MAR-1…MAR-n via curl, transition them, and read a correct history.
- The concurrency test is green and you can explain why.

Commit: `phase-07: issues, workflow rules, ranking, history`.

# Phase 08 · Boards, sprints, backlog (and the "Your work" read side)

**Goal:** the board and backlog endpoints return whole screens in one query each; moving an issue re-ranks/transitions/re-sprints it with a one-row update; sprints can be planned, started and completed.
**Spec:** 00-product §3.5–3.6, §4.4–4.6; 02-data-model (`sprint`, `board*`, indexes); 03-api §3.3, §3.4 (`move`), §3.7, §3.1 (`/me/*`). **Time:** 4 days.

## Concepts you'll learn

- **Read models in SQL** — shape the query like the screen.
- **Index-only scans and `INCLUDE` columns** — read `EXPLAIN (ANALYZE, BUFFERS)` output.
- **Pessimistic row locks** (`FOR NO KEY UPDATE`) vs optimistic locking — when to use which.
- **Partial unique indexes** as business rules ("one active sprint").
- **State machines** for sprint lifecycle.

## Files in this phase

1. `sprint/package-info.java`, `sprint/internal/Sprint.java`, `SprintRepository.java`, `sprint/SprintService.java`, sprint event payloads in `libs/contracts`
2. `sprint/web/SprintController.java` + records
3. `issue/IssueService.move(...)` + `issue/web` endpoint `POST /issues/{key}/move` + `IssueMoved` payload
4. `issue/IssueService.bulk(...)` + endpoint
5. `board/internal/BoardQueries.java` (`view`) + `board/web/BoardController.java`
6. `sprint/internal/BacklogQueries.java` + `GET /projects/{key}/backlog`
7. `home/package-info.java`, `home/internal/HomeQueries.java`, `home/web/MeWorkController.java` (`/me/work`, `/me/stats`, `/me/activity`)
8. Dev data: `apps/api/src/main/resources/db/dev/R__dev_seed.sql` (repeatable migration, loaded only in the `dev` profile via `spring.flyway.locations` in `application-dev.yml`) with 2 users, 3 projects, ~300 issues — so screens look like the canvas
9. Tests

### `Sprint.java` — a small state machine

| #   | Cue                                               | What                                                      |
| --- | ------------------------------------------------- | --------------------------------------------------------- |
| 1   | `// BLOCK 1: mapping + states`                    | `enum State { PLANNED, ACTIVE, CLOSED }` stored lowercase |
| 2   | `// BLOCK 2: start(name, goal, startsOn, endsOn)` | only from PLANNED; dates required, `endsOn > startsOn`    |
| 3   | `// BLOCK 3: complete(clock)`                     | only from ACTIVE → CLOSED, sets `completedAt`             |
| 4   | `// BLOCK 4: rename/updateGoal/updateDates`       | allowed in PLANNED and ACTIVE                             |

The partial unique index catches the race where two people start sprints at once; translate it to `SPRINT_ALREADY_ACTIVE`.

### `SprintService.complete(sprintId, moveOpenIssuesTo)`

1. lock sprint; 2. collect open issues (category ≠ done) ordered by rank; 3. move them to backlog (rank them at the **top** of the backlog with `Rank.nBetween(null, firstBacklogRank, n)`) or to the target sprint (bottom); 4. compute `SprintReport` (committed = points at start — taken from the report projector's first snapshot or recomputed from history; added = issues whose `moved` history entered this sprint after start); 5. `sprint.complete()`; 6. publish `SprintCompleted(snapshot, report)` and one `IssueMoved` per moved issue.

### `IssueService.move(key, MoveIssue cmd)` — the hot path

| #   | Cue                            | What                                                                                                                                                                                                                                                                          |
| --- | ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: load + lock`      | `SELECT … FOR NO KEY UPDATE` the issue (jOOQ `forNoKeyUpdate()`), check `expectedVersion` → 412                                                                                                                                                                               |
| 2   | `// BLOCK 2: status change?`   | if `toStatusId` differs → `workflow.requireTransition` + `issue.transitionTo`                                                                                                                                                                                                 |
| 3   | `// BLOCK 3: sprint change?`   | `toSprintId` present → validate same project, not closed → set                                                                                                                                                                                                                |
| 4   | `// BLOCK 4: rank`             | read **current** ranks of `beforeKey`/`afterKey` (must be in the destination list) → `Rank.between(beforeRank, afterRank)`; if neighbours are missing or out of order (someone moved them meanwhile) → fall back to "end of target list" and add warning `NEIGHBOURS_CHANGED` |
| 5   | `// BLOCK 5: WIP check`        | `board.columnFor(statusId)` → count issues in that column for the active sprint; over the limit → warning `WIP_LIMIT_EXCEEDED`                                                                                                                                                |
| 6   | `// BLOCK 6: history + events` | `moved` history row; `IssueMoved` (+ `IssueTransitioned` if the status changed)                                                                                                                                                                                               |

**Ask yourself:** why lock with `FOR NO KEY UPDATE` instead of relying only on `@Version`? (Hint: the rank is computed from neighbours read in the same transaction.) Why `NO KEY` instead of plain `FOR UPDATE`?

### `BoardQueries.view(projectKey, sprintSelector, filters)` — one query

```
select board columns ordered by position,
       multiset( select card fields from issue
                 where status_id = any(column.status_ids)
                   and sprint_id = :sprint   (kanban: sprint ignored, done column limited to last 14 days)
                   and deleted_at is null and <filters>
                 order by rank ) as cards
from board_column join board ...
```

plus two small maps in the same round trip (epics used by the cards, people used by the cards) via additional `multiset`s or a second query — measure both and keep the faster.
**Check:** with the dev seed, `EXPLAIN (ANALYZE, BUFFERS)` the inner card query and confirm an **Index Only Scan using ix_issue_board**. Paste the plan in your learning log and explain each line.

### `BacklogQueries` and `HomeQueries`

Same style. `HomeQueries.stats(userId)` computes the four tiles in one statement with `count(*) FILTER (WHERE …)` — learn the `FILTER` clause.

## Tests

- `SprintTest` (pure state machine).
- `SprintServiceIT`: start second sprint → 409; complete moves open issues to top of backlog in their original order; report numbers.
- `MoveIssueIT`: move within column, across columns (allowed / not allowed), into sprint, into backlog, stale version → 412, neighbours changed → warning; 20 concurrent moves of different issues into the same position all succeed with unique ranks.
- `BoardQueryIT`: board JSON matches a snapshot for the seed data; filter combinations.
- Performance smoke (`@Tag("perf")`): board view with 500 issues < 50 ms server time on your laptop (log it).

## Done when

- `GET /projects/MAR/board` returns the full board in one call and matches the canvas data shape.
- You can read the EXPLAIN plan and explain why it's fast.

Commit: `phase-08: boards, sprints, backlog, move`.

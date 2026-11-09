# Phase 14 · Board and backlog UI (drag and drop, optimistic moves, live updates)

**Goal:** the sprint board and backlog from the canvas, with drag and drop that feels instant, rolls back on failure, and shows other people's changes live.
**Spec:** 07-frontend §3–6; 03-api §3.3, §3.4 (`move`), §3.7, §4; 00-product §3.5–3.6. **Time:** 6 days.

## Concepts you'll learn

- **Optimistic updates** — snapshot, patch, roll back, reconcile with the server's answer.
- **Pure cache transforms** — `applyMove(board, move)` is a pure function you can unit-test without React.
- **Fractional indexing on the client** — same algorithm, same test vectors as Java.
- **Pragmatic drag and drop** — native HTML5 DnD under the hood, adapters, monitors, closest-edge hit-testing.
- **EventSource** — reconnection, `Last-Event-ID`, ordering by `sequence`.
- **Virtualisation** — rendering 1 000 rows by rendering ~20.
- **`<dialog>`** — modal semantics for free (focus trap, Esc, top layer).

## Files in this phase

1. `pnpm add @atlaskit/pragmatic-drag-and-drop @atlaskit/pragmatic-drag-and-drop-hitbox @atlaskit/pragmatic-drag-and-drop-auto-scroll @tanstack/react-virtual`
2. `src/shared/lib/rank.ts` + `rank.test.ts` (reads `docs/spec/fixtures/rank-vectors.json`)
3. `src/shared/api/sse.ts` + `sse.test.ts`
4. `src/app/ProjectShell.tsx` (project sidebar layout; opens the project SSE stream; renders the connection dot and presence)
5. `src/features/board/queries.ts`, `board-cache.ts` (`applyMove`, `applyRemoteIssue`, `removeIssue`) + `board-cache.test.ts`
6. `src/features/board/mutations.ts` (`useMoveIssue`)
7. `src/shared/ui/IssueCard.tsx` + `IssueCard.module.css` (the canvas component: default / flagged / done / dragging states)
8. `src/features/board/BoardPage.tsx`, `BoardHeader.tsx`, `BoardToolbar.tsx`, `BoardColumn.tsx`, `useBoardDnd.ts`
9. `src/features/board/live.ts` — SSE event → cache patch dispatcher
10. `src/shared/ui/Dialog.tsx` (wrapper around `<dialog>`), `Menu.tsx` (Popover API + anchor positioning)
11. `src/features/backlog/BacklogPage.tsx`, `SprintSection.tsx`, `BacklogRow.tsx`, `EpicsPanel.tsx`, `StartSprintDialog.tsx`, `CompleteSprintDialog.tsx`, `queries.ts`, `mutations.ts`
12. Tests

### File 2 — `rank.ts`

Port `Rank.java` block by block (alphabet, midpoint, between, nBetween). The test loads the JSON vectors produced in phase 07 and asserts identical output. **If one vector fails, the bug is in one of the two implementations — find which.**

### File 3 — `sse.ts`

| #   | Cue                           | What                                                                                                                                                             |
| --- | ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: types`           | `type StreamHandlers = Partial<Record<StreamEventName, (data: unknown, id: string) => void>> & { onStatus?: (s: 'open' \| 'reconnecting' \| 'closed') => void }` |
| 2   | `// BLOCK 2: open`            | `new EventSource(url, { withCredentials: true })`; `addEventListener(name, …)` for every handler (named events don't fire `onmessage`)                           |
| 3   | `// BLOCK 3: status`          | `onopen` → open; `onerror` → reconnecting (the browser retries automatically and sends `Last-Event-ID` for you)                                                  |
| 4   | `// BLOCK 4: close + cleanup` | returns `() => es.close()` for `useEffect` cleanup                                                                                                               |

**Ask yourself:** you never wrote code to send `Last-Event-ID` — who does, and when?

### File 5 — `board-cache.ts` (pure functions)

| Function                                                   | What                                                                                                                                                                        |
| ---------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `applyMove(board, {key, toStatusId, beforeKey, afterKey})` | remove card from its column, compute provisional rank with `rank.between(beforeRank, afterRank)`, insert into the target column at the right index, update counts/overLimit |
| `applyRemoteIssue(board, event)`                           | if `event.sequence <= card.version` → return board unchanged; else upsert/move the card; mark `flash: true`                                                                 |
| `removeIssue(board, key)`                                  |                                                                                                                                                                             |

All return **new objects** (no mutation) — the cache and React rely on referential changes.

### File 6 — `useMoveIssue`

Implements the onMutate/onError/onSuccess plan in 07-frontend §3 exactly. Add `mutationKey: ['move', projectKey]` and `scope: { id: 'board-moves' }` so rapid moves are **serialised** — explain why two in-flight moves of the same card could otherwise arrive out of order.

### File 8 — `useBoardDnd`

| #   | Cue                                 | What                                                                                                                                                                                                                                    |
| --- | ----------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: card draggable`        | `draggable({ element, getInitialData: () => ({ type: 'card', key, statusId, version }), onDragStart: () => setDragging(true), onDrop: () => setDragging(false) })`                                                                      |
| 2   | `// BLOCK 2: card as drop target`   | `dropTargetForElements({ element, canDrop: ({ source }) => source.data.type === 'card' && source.data.key !== key, getData: ({ input, element }) => attachClosestEdge({ key }, { input, element, allowedEdges: ['top', 'bottom'] }) })` |
| 3   | `// BLOCK 3: column as drop target` | accepts drops into empty space; `canDrop` checks the allowed transitions from the project's workflow → `data-drop-disabled` styling                                                                                                     |
| 4   | `// BLOCK 4: monitor`               | `monitorForElements({ onDrop({ source, location }) { … compute beforeKey/afterKey from the innermost target and its closest edge … move.mutate(...) } })`                                                                               |
| 5   | `// BLOCK 5: auto-scroll`           | `autoScrollForElements({ element: scrollerRef.current })`                                                                                                                                                                               |
| 6   | `// BLOCK 6: combine + cleanup`     | `combine(...)` returns one cleanup for `useEffect`                                                                                                                                                                                      |

Accessibility: every card also has a "Move to…" `Menu` with statuses and "Top of column / Bottom of column".

### File 9 — `live.ts`

`useProjectLive(projectKey)` subscribes via `sse.ts` and maps events to `board-cache` functions on both the `board` and `backlog` query data (`queryClient.setQueriesData({ queryKey: ['board', projectKey] }, …)`). On `resync` → `invalidateQueries({ queryKey: [..., projectKey] })`.

### Backlog

Each list (`SprintSection`) is virtualised with `useVirtualizer` once it has > 50 rows. Rows use **CSS subgrid** for aligned columns (08-css-volt §5). Drag between sections uses the same DnD pattern with `toSprintId`.

## Tests

- `rank.test.ts` — shared vectors.
- `board-cache.test.ts` — move within/between columns, stale remote events ignored, counts and WIP flags recomputed.
- `useMoveIssue.test.tsx` (MSW): success reconciles rank/version; 409 rolls back and toasts; 412 rolls back and refetches.
- `sse.test.ts` — with a fake `EventSource` class.
- Playwright (phase 18 runs it in CI, write it now): two browser contexts on the same board; move in A appears in B within 1 s.

## Decisions taken while building it

- **OpenAPI schema names (backend):** springdoc names a schema after the class's simple name and silently keeps one
  of two clashing classes — `BoardView.Column` and `ProjectDetails.Column` were both `Column`, so the board's
  generated type had no `cards`. Clashing nested records now carry `@Schema(name = …)` (`BoardColumn`,
  `BoardCard`, `BoardSprint`, `ProjectColumn`, `IssueSprint`, `BulkResult`, `TqlCompletion`), and
  `SchemaNamesTest` fails the build on any new clash.
- **Move request (backend):** `toStatusId`, `beforeKey` and `afterKey` are documented nullable, and generated clients
  send `null`; the api answered 400 "cannot be cleared". null now means "not given" (only `toSprintId` keeps its
  null = backlog meaning). `IssueApiIT` sends explicit nulls.
- **Presence (backend):** `presence` was broadcast only when the set changed, so a second tab — or anyone joining a
  set that already contained them — never heard who was there. Each new stream now gets the current set
  (`SseStreamIT.everyNewStreamIsToldWhoIsHere`, which fails without the fix).
- **No new workflow endpoint:** `GET /projects/{key}` (`ProjectDetails`) already has statuses and transitions; the
  board uses those for drop targets and the card menu.
- **Filters run in the browser** over one cached board / backlog — instant, and live updates patch one cache entry.
  Only the board's `recent` goes to the api (cards carry no `updatedAt`).
- **Moves read the version at send time:** in the `board-moves` scope the second move's `mutationFn` runs after the
  first one finished and takes the card's version from the cache then — not the stale one from drag time.
- **Rollback is per card** (`restoreCard`/`restoreRow`), not the whole snapshot: restoring the snapshot would also
  undo other moves that finished meanwhile. A later pending move of the same card makes `onSuccess` take only the
  version; live events for a card with a pending move are skipped (its answer, or its 412, sets it right).
- **Reconnects:** EventSource retries by itself (and sends `Last-Event-ID`); when it gives up (401/502) `sse.ts`
  reopens with backoff. A second `ready` means "reconnected with nothing to resume from" → refetch.
- **Live stream in `ProjectShell`**, not the board: one connection per open project; headers show it through
  `ProjectLiveContext` (connection dot + who's here).
- **Virtualised lists:** rows stay in the subgrid flow, with padding standing in for rows not drawn. The padding is
  on the list *inside* the scroll box — on the scroll box itself, a border-box can't be smaller than its padding,
  so `max-block-size` stops working and every row renders.
- **Swimlanes** (group by assignee/epic): a drop can't leave its lane — a move doesn't change assignee or epic.
- **Seeding 1 000 issues:** `infra/postgres/seed-big-backlog.sql` (dev only; scoped to the demo workspace, whose MAR
  isn't the only one); see infra/README.
- **Playwright** runs locally on the installed Chrome (`channel: 'chrome'`); tests restore what they move in a
  fixture (`restoreLater`) that is torn down before the pages close — a `finally` doesn't run in time on a timeout.
  The card menu is driven by keyboard, as its users do.

## Done when

- Dragging feels instant; with DevTools network throttled to "Slow 3G" the card still lands immediately, and an injected 409 snaps it back with a toast.
- Two windows stay in sync.
- The backlog scrolls smoothly with 1 000 issues (seed more with a dev endpoint or SQL).

Commit: `phase-14: board and backlog ui`.

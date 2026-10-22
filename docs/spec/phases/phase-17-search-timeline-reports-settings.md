# Phase 17 · Issues & filters, timeline, reports, settings, notifications, command palette

**Goal:** the remaining canvas screens, plus the global notification popover and command palette.
**Spec:** 00-product §3.9–3.13; 03-api §3.3, §3.8–3.10; 05-search §3–4; 08-css-volt §5. **Time:** 7 days.

## Concepts you'll learn

- **Building a query editor** — tokens, caret position, autocomplete listbox (ARIA combobox pattern).
- **Charts in plain SVG** — scales, axes, paths; accessible charts with a table fallback.
- **Date math for timelines** — mapping dates to grid columns.
- **Admin forms with optimistic concurrency** — editing a whole workflow with `If-Match`.
- **Keyboard-first UI** — global shortcuts that don't fire inside inputs.

## Backend additions

1. `reporting/ReportingService.java` + `reporting/internal/ReportQueries.java` + `reporting/web/ReportController.java` — burndown (read `sprint_day_snapshot`, compute the ideal line in Java over working days Mon–Fri), velocity (`sprint_report`), sprint report, breakdown (one `GROUP BY` query)
2. `project` timeline endpoint (`ProjectQueries.timeline`) — epics + children with dates and progress (`count(*) FILTER (WHERE status_category = 'done')`)
3. `notification/NotificationService.java` + controller (list, mark read, unread count)
4. `workflow` PUT endpoint (full replacement, `If-Match`, remap of deleted statuses in one transaction) + `WorkflowChanged` event
5. Tests for each (IT with seed data)

## Frontend

### Issues & filters (`features/search`)

| File               | Blocks                                                                                                                                                                                                                                                                                                                   |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `IssuesPage.tsx`   | layout: saved-filters nav · header · mode switch (search param `mode=basic\|query`) · results                                                                                                                                                                                                                            |
| `FilterPills.tsx`  | each pill = a `Menu` with checkboxes; builds TQL from pills (pills → TQL string is a pure function you unit-test)                                                                                                                                                                                                        |
| `TqlEditor.tsx`    | `<input role="combobox" aria-expanded aria-controls aria-activedescendant>` + `<ul role="listbox" popover>` anchored under the input; calls `/search/tql/complete` with the text up to `selectionStart` (deferred with `useDeferredValue`); ↑/↓/Enter/Tab/Esc handling; red underline at the error `position` from a 400 |
| `ResultsTable.tsx` | `<table>` with sticky header, sortable headers (`aria-sort`), row selection (`:has(input:checked)` styling), virtualised body; bulk action bar appears via `:has()` on the container when any row is checked                                                                                                             |
| `useSearch.ts`     | `useInfiniteQuery` with `nextCursor`                                                                                                                                                                                                                                                                                     |
| `ExportButton.tsx` | `POST /search/export` → blob → download link                                                                                                                                                                                                                                                                             |

### Timeline (`features/timeline`)

Grid with one column per week (`grid-template-columns: 16rem repeat(var(--weeks), minmax(3.5rem, 1fr))`), bars placed with `grid-column: var(--start) / var(--end)` set from data as CSS custom properties; today line positioned with a percentage custom property; expand/collapse epics; drag a bar's end handle (pointer events + `setPointerCapture`) to change `dueDate` (optimistic patch).

### Reports (`features/reports`)

| File                                  | What                                                                                                                                                                                                                              |
| ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `scale.ts`                            | `linearScale(domain, range)` and `bandScale` — 15 lines each, unit-tested; no chart library                                                                                                                                       |
| `Burndown.tsx`                        | `<svg viewBox>` with gridlines, ideal dashed line, remaining step path (`M … H … V …`), scope-change marker, `<title>` per point for hover; `role="img"` + `aria-label` summary; a "Show data table" `<details>` with the numbers |
| `Velocity.tsx`                        | grouped bars (committed = neutral, completed = `--chart-1`), legend, data table                                                                                                                                                   |
| `StatusBreakdown.tsx`, `Workload.tsx` | flex bars with CSS custom property widths                                                                                                                                                                                         |

Colours only from the chart tokens; never colour alone — legends and labels always present.

### Settings (`features/settings`)

`SettingsPage` with tabs per section (search param). **Workflow editor:** list of statuses (drag to reorder with the same DnD lib), category select, WIP input, transitions matrix (checkbox grid `from × to`) — save sends the whole workflow with `If-Match`; 412 → show "Someone changed the workflow" with a reload button. **People:** table with role `<select>`s (PATCH per change) and remove (confirm `<dialog>`).

### Global

- `NotificationsPopover.tsx` — bell button → popover list; `useMeStream` (SSE `/me/stream`) updates the unread badge and prepends items; "Mark all read".
- `CommandPalette.tsx` — `<dialog>` opened with Ctrl/Cmd+K; one input + listbox; sources: issue key match (`/search/suggest`), navigation targets, actions for the current issue (transitions); fuzzy match in the client for static items.
- `app/keyboard.ts` — one `keydown` listener on `document`; ignore when `event.target` is editable (`input`, `textarea`, `[contenteditable]`); sequences like `g b` with a 1 s timeout.

## Tests

- `pillsToTql.test.ts`, `scale.test.ts`, `TqlEditor.test.tsx` (keyboard navigation, error underline), `keyboard.test.ts`.
- Backend ITs for reports with a fixed `Clock`.
- Playwright: run a TQL query, save it as a filter, star it; burndown renders with the dev seed.

## Done when

- Every screen in the canvas exists and works against real data.
- The burndown matches numbers you compute by hand from the seed for one sprint.

Commit: `phase-17: search ui, timeline, reports, settings, global ui`.

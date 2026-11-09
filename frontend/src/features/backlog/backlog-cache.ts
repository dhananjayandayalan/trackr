import type { IssueState } from '@/shared/api/issue-state.ts'
import type { BacklogRow, BacklogView, SprintList } from '@/shared/api/types.ts'
import { between, compareRanks } from '@/shared/lib/rank.ts'

/**
 * The backlog cache's transforms — the board's ideas (board-cache.ts) on the backlog's shape: several lists
 * (active sprint, planned sprints, backlog), each ordered by rank. A list is named by its sprint id; the
 * backlog itself is `null`, as in the move request (03-api §3.4: toSprintId null = backlog).
 */

// BLOCK 1: the cached shape
export type LiveRow = BacklogRow & { flash?: number }
export type LiveSprintList = Omit<SprintList, 'issues'> & { issues: LiveRow[] }
export type LiveBacklog = Omit<BacklogView, 'activeSprint' | 'plannedSprints' | 'backlog'> & {
  activeSprint?: LiveSprintList | null
  plannedSprints: LiveSprintList[]
  backlog: Omit<BacklogView['backlog'], 'issues'> & { issues: LiveRow[] }
}
export type ListId = string | null

export interface BacklogMove {
  key: string
  toSprintId: ListId
  beforeKey: string | null
  afterKey: string | null
}

export type StatusLookup = (statusId: string) => { name: string; category: string } | undefined

export function findRow(view: LiveBacklog, key: string): { row: LiveRow; listId: ListId } | undefined {
  for (const [listId, rows] of lists(view)) {
    const row = rows.find((r) => r.key === key)
    if (row) {
      return { row, listId }
    }
  }
  return undefined
}

export function rowsOf(view: LiveBacklog, listId: ListId): LiveRow[] | undefined {
  return lists(view).find(([id]) => id === listId)?.[1]
}

// BLOCK 2: optimistic move between (or within) lists
export function applyBacklogMove(view: LiveBacklog, move: BacklogMove): LiveBacklog {
  const found = findRow(view, move.key)
  if (!found || rowsOf(view, move.toSprintId) === undefined) {
    return view
  }
  const removed = withoutRow(view, move.key)
  const target = rowsOf(removed, move.toSprintId) ?? []
  const before = move.beforeKey === null ? undefined : target.find((r) => r.key === move.beforeKey)
  const after = move.afterKey === null ? undefined : target.find((r) => r.key === move.afterKey)
  let rank = found.row.rank
  if (before || after) {
    try {
      rank = between(before?.rank ?? null, after?.rank ?? null)
    } catch {
      // stale neighbours: keep the rank, the server's answer fixes it
    }
  }
  const index = after
    ? target.indexOf(after)
    : before
      ? target.indexOf(before) + 1
      : sortedIndex(target, rank)
  return withRowAt(removed, move.toSprintId, { ...found.row, rank }, index)
}

// BLOCK 3: the server's version of an issue. Epics (their own panel) and subtasks (they travel with their
// parent) are never rows; a sprint we don't show (a closed one) means the row leaves the backlog screen.
export function applyBacklogIssue(
  view: LiveBacklog,
  issue: IssueState,
  statuses: StatusLookup,
  options: { flash?: number } = {},
): LiveBacklog {
  const existing = findRow(view, issue.key)
  if (existing && existing.row.version >= issue.version) {
    return view
  }
  const next = existing ? withoutRow(view, issue.key) : view
  const target = rowsOf(next, issue.sprintId)
  if (issue.deleted || issue.type === 'epic' || issue.type === 'subtask' || target === undefined) {
    return next
  }
  const status = statuses(issue.statusId)
  const row: LiveRow = {
    id: issue.id,
    key: issue.key,
    summary: issue.summary,
    type: issue.type,
    priority: issue.priority,
    statusId: issue.statusId,
    statusName: status?.name ?? existing?.row.statusName ?? '',
    statusCategory: issue.statusCategory,
    assigneeId: issue.assigneeId,
    storyPoints: issue.storyPoints,
    epicId: issue.epicId === undefined ? (existing?.row.epicId ?? null) : issue.epicId,
    rank: issue.rank,
    flagged: issue.flagged,
    version: issue.version,
    ...(options.flash === undefined ? {} : { flash: options.flash }),
  }
  return withRowAt(next, issue.sprintId, row, sortedIndex(target, row.rank))
}

export function removeBacklogIssue(view: LiveBacklog, key: string): LiveBacklog {
  return findRow(view, key) ? withoutRow(view, key) : view
}

/** One row back to where `previous` had it; everything else stays as it is now. */
export function restoreRow(view: LiveBacklog, previous: LiveBacklog, key: string): LiveBacklog {
  const before = findRow(previous, key)
  const now = findRow(view, key)
  if (!before) {
    return removeBacklogIssue(view, key)
  }
  if (now && now.row.version > before.row.version) {
    return view
  }
  const removed = withoutRow(view, key)
  const rows = rowsOf(removed, before.listId)
  if (rows === undefined) {
    return removed
  }
  return withRowAt(removed, before.listId, before.row, sortedIndex(rows, before.row.rank))
}

export function setRowVersion(view: LiveBacklog, key: string, version: number): LiveBacklog {
  const found = findRow(view, key)
  if (!found || found.row.version >= version) {
    return view
  }
  return mapList(view, found.listId, (rows) =>
    rows.map((row) => (row.key === key ? { ...row, version } : row)),
  )
}

/** Story points per status category — the coloured totals in a list's header. */
export function pointsOf(rows: readonly BacklogRow[]): { todo: number; inProgress: number; done: number } {
  const points = { todo: 0, inProgress: 0, done: 0 }
  for (const row of rows) {
    const value = row.storyPoints ?? 0
    if (row.statusCategory === 'done') points.done += value
    else if (row.statusCategory === 'in_progress') points.inProgress += value
    else points.todo += value
  }
  // one decimal, as the api stores them; summing 0.1s in binary floating point would show 0.30000000000000004
  return {
    todo: Math.round(points.todo * 10) / 10,
    inProgress: Math.round(points.inProgress * 10) / 10,
    done: Math.round(points.done * 10) / 10,
  }
}

// BLOCK 4: helpers
function lists(view: LiveBacklog): [ListId, LiveRow[]][] {
  return [
    ...(view.activeSprint
      ? [[view.activeSprint.sprint.id, view.activeSprint.issues] as [ListId, LiveRow[]]]
      : []),
    ...view.plannedSprints.map((list): [ListId, LiveRow[]] => [list.sprint.id, list.issues]),
    [null, view.backlog.issues],
  ]
}

function mapList(view: LiveBacklog, listId: ListId, change: (rows: LiveRow[]) => LiveRow[]): LiveBacklog {
  if (listId === null) {
    return { ...view, backlog: { ...view.backlog, issues: change(view.backlog.issues) } }
  }
  if (view.activeSprint?.sprint.id === listId) {
    return { ...view, activeSprint: { ...view.activeSprint, issues: change(view.activeSprint.issues) } }
  }
  return {
    ...view,
    plannedSprints: view.plannedSprints.map((list) =>
      list.sprint.id === listId ? { ...list, issues: change(list.issues) } : list,
    ),
  }
}

function withoutRow(view: LiveBacklog, key: string): LiveBacklog {
  const found = findRow(view, key)
  return found ? mapList(view, found.listId, (rows) => rows.filter((row) => row.key !== key)) : view
}

function withRowAt(view: LiveBacklog, listId: ListId, row: LiveRow, index: number): LiveBacklog {
  return mapList(view, listId, (rows) => {
    const next = [...rows]
    next.splice(Math.max(0, Math.min(index, next.length)), 0, row)
    return next
  })
}

function sortedIndex(rows: readonly LiveRow[], rank: string): number {
  const index = rows.findIndex((row) => compareRanks(row.rank, rank) > 0)
  return index === -1 ? rows.length : index
}

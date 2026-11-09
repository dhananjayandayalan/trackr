import type { BoardSearch } from '@/app/router.tsx'
import type { ProjectTransition } from '@/shared/api/types.ts'
import type { LiveBoard, LiveCard } from './board-cache.ts'

/** The board's filters and grouping, as plain functions of the cached board — what the screen derives. */

// BLOCK 1: the workflow (00-product §4.3): a transition from this status, or from any status (from = null)
export function transitionAllowed(
  transitions: readonly ProjectTransition[],
  from: string,
  to: string,
): boolean {
  return (
    from !== to &&
    transitions.some((t) => t.toStatusId === to && (t.fromStatusId == null || t.fromStatusId === from))
  )
}

// BLOCK 2: filters — everything but `recent` runs here, over the cached cards
export function cardFilter(search: BoardSearch, meId: string): (card: LiveCard) => boolean {
  const q = search.q?.trim().toLowerCase() ?? ''
  const assignees = search.assignee ?? []
  return (card) =>
    (!search.mine || card.assigneeId === meId) &&
    (!search.bugs || card.type === 'bug') &&
    (assignees.length === 0 || assignees.includes(card.assigneeId ?? UNASSIGNED)) &&
    (q === '' || card.summary.toLowerCase().includes(q) || card.key.toLowerCase() === q)
}

export const UNASSIGNED = 'unassigned'
export const NO_EPIC = 'no-epic'
export const ALL = 'all'

export interface Lane {
  id: string
  /** null: the only lane (no grouping) — no lane header */
  title: string | null
}

// BLOCK 3: swimlanes. Grouped by assignee: the people on the board by name, then "Unassigned". By epic: the
// project's epics in order, then "No epic". Only lanes with a visible card are drawn.
export function lanesOf(board: LiveBoard, group: BoardSearch['group'], visible: readonly LiveCard[]): Lane[] {
  if (group === undefined || group === 'none') {
    return [{ id: ALL, title: null }]
  }
  const used = new Set(visible.map((card) => laneOf(card, group)))
  const lanes: Lane[] =
    group === 'assignee'
      ? [
          ...Object.entries(board.people).map(([id, person]) => ({ id, title: person.name })),
          { id: UNASSIGNED, title: 'Unassigned' },
        ]
      : [
          ...Object.entries(board.epics).map(([id, epic]) => ({ id, title: `${epic.key} · ${epic.name}` })),
          { id: NO_EPIC, title: 'No epic' },
        ]
  return lanes.filter((lane) => used.has(lane.id))
}

export function laneOf(card: LiveCard, group: BoardSearch['group']): string {
  if (group === 'assignee') return card.assigneeId ?? UNASSIGNED
  if (group === 'epic') return card.epicId ?? NO_EPIC
  return ALL
}

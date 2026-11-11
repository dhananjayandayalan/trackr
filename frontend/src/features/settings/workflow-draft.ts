import type { ReplaceWorkflowRequest, WorkflowView } from '@/shared/api/types.ts'

/**
 * The workflow editor's state, as plain data with pure updates: easy to test, and the component only renders it.
 * Statuses keep their ids (new ones get a fresh UUID here, so transitions can point at them before they exist on
 * the server); transitions are "from|to" strings in a Set — a checkbox in the matrix is one string in or out.
 */

export type Category = 'todo' | 'in_progress' | 'done'

export interface DraftStatus {
  id: string
  name: string
  category: Category
  color: string
  wipLimit: number | null
  /** false for a status added in this edit */
  saved: boolean
}

export interface WorkflowDraft {
  statuses: DraftStatus[]
  /** "from|to"; from = ANY for "from any status" */
  transitions: ReadonlySet<string>
  /** saved statuses that were deleted: id → the status their issues move to ('' = not chosen yet) */
  removed: Readonly<Record<string, string>>
  /** names of the removed statuses, for the "move their issues to" list */
  removedNames: Readonly<Record<string, string>>
}

export const ANY = 'any'
const categoryColor: Record<Category, string> = { todo: 'todo', in_progress: 'progress', done: 'done' }

export const pair = (from: string | null | undefined, to: string) => `${from ?? ANY}|${to}`

// BLOCK 1: from the server and back
export function draftOf(workflow: WorkflowView): WorkflowDraft {
  return {
    statuses: workflow.statuses.map((status) => ({
      id: status.id,
      name: status.name,
      category: (['todo', 'in_progress', 'done'].includes(status.category)
        ? status.category
        : 'todo') as Category,
      color: status.color,
      wipLimit: status.wipLimit ?? null,
      saved: true,
    })),
    transitions: new Set(workflow.transitions.map((t) => pair(t.fromStatusId, t.toStatusId))),
    removed: {},
    removedNames: {},
  }
}

export function requestOf(draft: WorkflowDraft): ReplaceWorkflowRequest {
  const ids = new Set(draft.statuses.map((status) => status.id))
  return {
    statuses: draft.statuses.map((status) => ({
      id: status.id,
      name: status.name.trim(),
      category: status.category,
      color: status.color,
      wipLimit: status.wipLimit,
    })),
    transitions: [...draft.transitions].flatMap((key) => {
      const [from = '', to = ''] = key.split('|')
      // a transition to or from a status that was removed goes with it
      if (!ids.has(to) || (from !== ANY && !ids.has(from))) {
        return []
      }
      return [{ fromStatusId: from === ANY ? null : from, toStatusId: to }]
    }),
    remapTo: Object.fromEntries(Object.entries(draft.removed).filter(([, to]) => to !== '' && ids.has(to))),
  }
}

// BLOCK 2: edits
export function addStatus(draft: WorkflowDraft, id: string, name: string): WorkflowDraft {
  const status: DraftStatus = { id, name, category: 'todo', color: 'todo', wipLimit: null, saved: false }
  // a new status can be reached from anywhere, like the templates' statuses
  return {
    ...draft,
    statuses: [...draft.statuses, status],
    transitions: new Set([...draft.transitions, pair(null, id)]),
  }
}

export function updateStatus(
  draft: WorkflowDraft,
  id: string,
  change: Partial<Omit<DraftStatus, 'id' | 'saved'>>,
) {
  return {
    ...draft,
    statuses: draft.statuses.map((status) => {
      if (status.id !== id) {
        return status
      }
      const next = { ...status, ...change }
      // a new category brings its default colour, unless the colour was changed in the same edit
      if (change.category && !change.color) {
        next.color = categoryColor[change.category]
      }
      return next
    }),
  }
}

export function removeStatus(draft: WorkflowDraft, id: string): WorkflowDraft {
  const status = draft.statuses.find((s) => s.id === id)
  if (!status) {
    return draft
  }
  const statuses = draft.statuses.filter((s) => s.id !== id)
  // a removed status that others were mapped to can't be a target any more
  const removed = Object.fromEntries(
    Object.entries(draft.removed).map(([from, to]) => [from, to === id ? '' : to]),
  )
  return status.saved
    ? {
        ...draft,
        statuses,
        removed: { ...removed, [id]: '' },
        removedNames: { ...draft.removedNames, [id]: status.name },
      }
    : { ...draft, statuses, removed }
}

export function move(draft: WorkflowDraft, from: number, to: number): WorkflowDraft {
  if (from === to || from < 0 || to < 0 || from >= draft.statuses.length || to >= draft.statuses.length) {
    return draft
  }
  const statuses = [...draft.statuses]
  const [moved] = statuses.splice(from, 1)
  if (moved) {
    statuses.splice(to, 0, moved)
  }
  return { ...draft, statuses }
}

export function toggleTransition(
  draft: WorkflowDraft,
  from: string | null,
  to: string,
  on: boolean,
): WorkflowDraft {
  const transitions = new Set(draft.transitions)
  if (on) {
    transitions.add(pair(from, to))
  } else {
    transitions.delete(pair(from, to))
  }
  return { ...draft, transitions }
}

export function remap(draft: WorkflowDraft, removedId: string, to: string): WorkflowDraft {
  return { ...draft, removed: { ...draft.removed, [removedId]: to } }
}

// BLOCK 3: what stops a save before the server has to say it
export function problemsOf(draft: WorkflowDraft): string[] {
  const problems: string[] = []
  const names = draft.statuses.map((status) => status.name.trim().toLowerCase())
  if (draft.statuses.length === 0) {
    problems.push('A workflow needs at least one status.')
  }
  if (names.some((name) => name === '')) {
    problems.push('Every status needs a name.')
  }
  if (new Set(names).size !== names.length) {
    problems.push('Two statuses have the same name.')
  }
  if (!draft.statuses.some((status) => status.category === 'todo')) {
    problems.push('New issues start in a To do status: keep at least one.')
  }
  for (const [id, to] of Object.entries(draft.removed)) {
    if (to === '') {
      problems.push(`Choose where the issues of ${draft.removedNames[id] ?? 'the removed status'} go.`)
    }
  }
  return problems
}

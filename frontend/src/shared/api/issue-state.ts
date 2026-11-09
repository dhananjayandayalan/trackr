import { z } from 'zod'
import type { IssueDetails } from './types.ts'

/**
 * One issue as the list caches need it, whatever told us about it: the move endpoint's answer (IssueDetails)
 * or a live event's snapshot (IssueSnapshot, 04-events §4). The board and backlog caches take only this, so
 * they don't care which of the two it came from.
 */
export interface IssueState {
  id: string
  key: string
  summary: string
  type: string
  priority: string
  storyPoints: number | null
  assigneeId: string | null
  /** undefined = not known: a subtask's epic is its parent's parent, which neither source carries */
  epicId: string | null | undefined
  flagged: boolean
  rank: string
  statusId: string
  statusCategory: string
  sprintId: string | null
  version: number
  deleted: boolean
}

// BLOCK 1: the snapshot on the wire. zod, never a cast: an event from a newer api (or a bug) is dropped,
// not half-applied. looseObject: fields we don't use (labels, dueDate, …) may come and go.
export const issueSnapshotSchema = z.looseObject({
  id: z.string(),
  key: z.string(),
  type: z.string(),
  summary: z.string(),
  statusId: z.string(),
  statusCategory: z.string(),
  priority: z.string(),
  assigneeId: z.string().nullable(),
  parentId: z.string().nullable(),
  sprintId: z.string().nullable(),
  storyPoints: z.number().nullable(),
  rank: z.string(),
  flagged: z.boolean(),
  deleted: z.boolean(),
  version: z.number(),
})

export type IssueSnapshot = z.infer<typeof issueSnapshotSchema>

// BLOCK 2: the two sources → one shape. A story, bug or task can only have an epic as its parent
// (00-product §4.2), so its parent is its epic; an epic has none; a subtask's epic we can't tell.
export function fromSnapshot(snapshot: IssueSnapshot): IssueState {
  return {
    id: snapshot.id,
    key: snapshot.key,
    summary: snapshot.summary,
    type: snapshot.type,
    priority: snapshot.priority,
    storyPoints: snapshot.storyPoints,
    assigneeId: snapshot.assigneeId,
    epicId: epicOf(snapshot.type, snapshot.parentId),
    flagged: snapshot.flagged,
    rank: snapshot.rank,
    statusId: snapshot.statusId,
    statusCategory: snapshot.statusCategory,
    sprintId: snapshot.sprintId,
    version: snapshot.version,
    deleted: snapshot.deleted,
  }
}

export function fromDetails(issue: IssueDetails): IssueState {
  return {
    id: issue.id,
    key: issue.key,
    summary: issue.summary,
    type: issue.type,
    priority: issue.priority,
    storyPoints: issue.storyPoints ?? null,
    assigneeId: issue.assignee?.id ?? null,
    epicId: issue.type === 'subtask' ? undefined : issue.parent?.type === 'epic' ? issue.parent.id : null,
    flagged: issue.flagged,
    rank: issue.rank,
    statusId: issue.status.id,
    statusCategory: issue.status.category,
    sprintId: issue.sprint?.id ?? null,
    version: issue.version,
    deleted: false,
  }
}

function epicOf(type: string, parentId: string | null): string | null | undefined {
  if (type === 'subtask') {
    return undefined
  }
  return type === 'epic' ? null : parentId
}

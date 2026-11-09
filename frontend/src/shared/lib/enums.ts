import type { LozengeTone } from '@/shared/ui/Lozenge.tsx'
import type { Priority } from '@/shared/ui/PriorityIcon.tsx'
import type { IssueType } from '@/shared/ui/TypeIcon.tsx'

// The api types these as plain strings in a few records (IssueRow, activity). These narrow them to the
// unions the components take, with a safe fallback instead of a cast that could lie.

const issueTypes: readonly string[] = ['story', 'bug', 'task', 'epic', 'subtask']
const priorities: readonly string[] = ['highest', 'high', 'medium', 'low', 'lowest']
const tones: readonly string[] = ['todo', 'progress', 'review', 'done', 'blocked']

export function issueType(value: string): IssueType {
  return issueTypes.includes(value) ? (value as IssueType) : 'task'
}

export function priority(value: string): Priority {
  return priorities.includes(value) ? (value as Priority) : 'medium'
}

/** A workflow status's colour token (todo, progress, review, done) is also its Lozenge tone. */
export function statusTone(color: string): LozengeTone {
  return tones.includes(color) ? (color as LozengeTone) : 'todo'
}

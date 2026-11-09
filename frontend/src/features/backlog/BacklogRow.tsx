import { Link } from '@tanstack/react-router'
import { useRef } from 'react'
import type { EpicSummary, MemberView } from '@/shared/api/types.ts'
import { issueType, priority } from '@/shared/lib/enums.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Lozenge, type LozengeTone } from '@/shared/ui/Lozenge.tsx'
import { PriorityIcon } from '@/shared/ui/PriorityIcon.tsx'
import { TypeIcon } from '@/shared/ui/TypeIcon.tsx'
import type { ListId, LiveRow } from './backlog-cache.ts'
import { useRowDnd } from './useBacklogDnd.ts'
import styles from './BacklogRow.module.css'

const tones: Partial<Record<string, LozengeTone>> = { todo: 'todo', in_progress: 'progress', done: 'done' }

/**
 * One issue in a backlog list. A subgrid row: its cells sit on the list's column tracks (08-css-volt §5), so
 * keys, epics, statuses and points line up down the list whatever each row contains.
 */
export function BacklogRow({
  projectKey,
  row,
  listId,
  epic,
  assignee,
  canEdit,
  index,
}: {
  projectKey: string
  row: LiveRow
  listId: ListId
  epic: EpicSummary | undefined
  assignee: MemberView | undefined
  canEdit: boolean
  /** position in the list, for the virtualiser's measurements */
  index?: number
}) {
  const ref = useRef<HTMLLIElement>(null)
  const { dragging, edge } = useRowDnd(ref, { key: row.key, listId, version: row.version }, canEdit)
  const tone = tones[row.statusCategory] ?? 'todo'

  return (
    <li
      ref={ref}
      className={styles.root}
      data-index={index}
      data-dragging={dragging || undefined}
      data-drop-edge={edge ?? undefined}
      data-flagged={row.flagged || undefined}
    >
      {row.flash !== undefined && <span key={row.flash} className={styles.flash} aria-hidden="true" />}
      <span className={styles.handle} aria-hidden="true">
        <Icon name="drag" size="sm" />
      </span>
      <TypeIcon type={issueType(row.type)} />
      <span className={styles.key}>{row.key}</span>
      <Link
        to="/p/$projectKey/issues/$issueKey"
        params={{ projectKey, issueKey: row.key }}
        className={styles.summary}
        draggable={false}
      >
        {row.summary}
      </Link>
      <span className={styles.epic}>
        {epic && (
          <span className={styles.epicTag} data-color={epic.color} title={`Epic: ${epic.summary}`}>
            {epic.summary}
          </span>
        )}
      </span>
      <span className={styles.status}>
        <Lozenge tone={tone}>{row.statusName}</Lozenge>
      </span>
      <span className={styles.points} title="Story points">
        {row.storyPoints ?? '–'}
      </span>
      <PriorityIcon priority={priority(row.priority)} />
      <span className={styles.assignee}>
        {assignee ? (
          <Avatar
            name={assignee.displayName}
            initials={assignee.initials}
            color={avatarColor(assignee.avatarColor)}
            size="sm"
          />
        ) : (
          <span className="visually-hidden">Unassigned</span>
        )}
      </span>
    </li>
  )
}

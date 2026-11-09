import type { ComponentPropsWithRef, ReactNode } from 'react'
import { Avatar, type AvatarColor } from './Avatar.tsx'
import { Icon } from './Icon.tsx'
import { PriorityIcon, type Priority } from './PriorityIcon.tsx'
import { TypeIcon, type IssueType } from './TypeIcon.tsx'
import styles from './IssueCard.module.css'

export type CardEdge = 'top' | 'bottom'

type IssueCardProps = Omit<ComponentPropsWithRef<'article'>, 'title'> & {
  issueKey: string
  /** the summary, usually wrapped in a link to the issue by the caller (this component knows no routes) */
  title: ReactNode
  type: IssueType
  priority: Priority
  storyPoints?: number | null
  flagged?: boolean
  /** in a done-category status: quieter, the key struck through */
  done?: boolean
  epic?: { name: string; color: string } | null
  assignee?: { name: string; initials: string; color: AvatarColor } | null
  /** this card is the one being dragged: it stays in place as a faded placeholder */
  dragging?: boolean
  /** a drop here would land above or below this card: draws the drop line on that edge */
  dropEdge?: CardEdge | null
  /** changed by someone else at this time: the flash animation (a new value replays it) */
  flash?: number
  /** the card's actions (the "Move to…" menu) */
  actions?: ReactNode
}

/**
 * The board card (08-css-volt §7: lift on hover, drag, flash). Presentational: the board wires drag and drop
 * to its element through `ref`, and states arrive as data attributes, so the CSS owns every look.
 */
export function IssueCard({
  issueKey,
  title,
  type,
  priority,
  storyPoints,
  flagged = false,
  done = false,
  epic,
  assignee,
  dragging = false,
  dropEdge,
  flash,
  actions,
  className,
  ...rest
}: IssueCardProps) {
  return (
    <article
      className={className ? `${styles.root} ${className}` : styles.root}
      data-flagged={flagged || undefined}
      data-done={done || undefined}
      data-dragging={dragging || undefined}
      data-drop-edge={dropEdge ?? undefined}
      aria-label={`${issueKey}${flagged ? ', flagged' : ''}`}
      {...rest}
    >
      {/* BLOCK 1: the flash — a layer under the content, remounted (key) for each new change so its
          animation starts over; nothing has to reset it */}
      {flash !== undefined && <span key={flash} className={styles.flash} aria-hidden="true" />}

      <div className={styles.top}>
        {epic && (
          <span className={styles.epic} data-color={epic.color} title={`Epic: ${epic.name}`}>
            {epic.name}
          </span>
        )}
        {flagged && (
          <span className={styles.flag} title="Flagged">
            <Icon name="flag" size="xs" label="Flagged" />
          </span>
        )}
      </div>
      {actions && <span className={styles.actions}>{actions}</span>}

      <h3 className={styles.title}>{title}</h3>

      {/* BLOCK 2: the footer — what you scan for: type and key on one side, points, priority, owner on the other */}
      <div className={styles.footer}>
        <TypeIcon type={type} />
        <span className={styles.key}>{issueKey}</span>
        <span className={styles.spacer} />
        {storyPoints != null && (
          <span className={styles.points} title="Story points">
            {storyPoints}
          </span>
        )}
        <PriorityIcon priority={priority} />
        {assignee ? (
          <Avatar name={assignee.name} initials={assignee.initials} color={assignee.color} size="sm" />
        ) : (
          <span className={styles.unassigned} role="img" aria-label="Unassigned" title="Unassigned">
            <Icon name="user" size="xs" />
          </span>
        )}
      </div>
    </article>
  )
}

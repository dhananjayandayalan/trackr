import { Link } from '@tanstack/react-router'
import { useRef } from 'react'
import type { ProjectDetails } from '@/shared/api/types.ts'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { IssueCard } from '@/shared/ui/IssueCard.tsx'
import { Menu, MenuGroup, MenuItem } from '@/shared/ui/Menu.tsx'
import { issueType, priority } from '@/shared/lib/enums.ts'
import type { LiveBoard, LiveCard, LiveColumn } from './board-cache.ts'
import { useCardDnd, useColumnDrop, type CardDragData } from './useBoardDnd.ts'
import styles from './BoardColumn.module.css'

/** A column's head: name, card count, WIP limit. Over the limit, the whole column shows it (08-css-volt §5). */
export function BoardColumnHeader({ column }: { column: LiveColumn }) {
  return (
    <div className={styles.header} data-over-limit={column.overLimit || undefined}>
      <h2 className={styles.name}>{column.name}</h2>
      <span className={styles.count}>
        {column.count}
        {column.wipLimit != null && <span className={styles.limit}> / {column.wipLimit}</span>}
        {column.overLimit && <span className="visually-hidden">, over the WIP limit</span>}
      </span>
    </div>
  )
}

interface BoardColumnProps {
  projectKey: string
  column: LiveColumn
  lane: string
  cards: LiveCard[]
  board: LiveBoard
  project: ProjectDetails
  canEdit: boolean
  /** a card is being dragged that can't go here */
  dropDisabled: boolean
  accepts: (source: CardDragData) => boolean
  allowed: (from: string, to: string) => boolean
  onMoveTo: (key: string, toStatusId: string, position: 'keep' | 'top' | 'bottom') => void
}

/** One column in one lane: a drop target, and the list of its cards. */
export function BoardColumn(props: BoardColumnProps) {
  const { column, lane, cards, dropDisabled, accepts } = props
  const ref = useRef<HTMLUListElement>(null)
  const over = useColumnDrop(ref, { columnId: column.id, lane }, accepts)
  return (
    <ul
      ref={ref}
      role="list"
      aria-label={column.name}
      className={styles.cell}
      data-over={over || undefined}
      data-over-limit={column.overLimit || undefined}
      data-drop-disabled={dropDisabled || undefined}
    >
      {cards.map((card) => (
        <li key={card.key} className={styles.item}>
          <BoardCard {...props} card={card} />
        </li>
      ))}
    </ul>
  )
}

function BoardCard({
  projectKey,
  column,
  lane,
  card,
  board,
  project,
  canEdit,
  accepts,
  allowed,
  onMoveTo,
}: BoardColumnProps & { card: LiveCard }) {
  const ref = useRef<HTMLElement>(null)
  const { dragging, edge } = useCardDnd(
    ref,
    {
      type: 'card',
      key: card.key,
      statusId: card.statusId,
      columnId: column.id,
      lane,
      version: card.version,
    },
    accepts,
    canEdit,
  )
  const status = project.statuses.find((s) => s.id === card.statusId)
  const epic = card.epicId ? board.epics[card.epicId] : undefined
  const person = card.assigneeId ? board.people[card.assigneeId] : undefined
  // the menu's targets: statuses on this board the workflow allows from here
  const targets = project.statuses.filter(
    (s) => allowed(card.statusId, s.id) && board.columns.some((c) => c.statusIds.includes(s.id)),
  )

  return (
    <IssueCard
      ref={ref}
      issueKey={card.key}
      title={
        // draggable={false}: a link is draggable on its own, and would win over the card.
        // Same page, plus ?issue=KEY: the side panel slides in over the board (a view transition)
        <Link
          to="/p/$projectKey/board"
          params={{ projectKey }}
          search={(prev) => ({ ...prev, issue: card.key })}
          viewTransition
          draggable={false}
        >
          {card.summary}
        </Link>
      }
      type={issueType(card.type)}
      priority={priority(card.priority)}
      storyPoints={card.storyPoints ?? null}
      flagged={card.flagged}
      done={status?.category === 'done'}
      epic={epic ? { name: epic.name, color: epic.color } : null}
      assignee={
        person ? { name: person.name, initials: person.initials, color: avatarColor(person.color) } : null
      }
      dragging={dragging}
      dropEdge={edge}
      {...(card.flash === undefined ? {} : { flash: card.flash })}
      actions={
        canEdit && (
          // the keyboard way to do what dragging does (07-frontend §4, an accessibility requirement)
          <Menu label={`Move ${card.key}`}>
            <MenuGroup label="In this column">
              <MenuItem
                onSelect={() => {
                  onMoveTo(card.key, card.statusId, 'top')
                }}
              >
                Top of column
              </MenuItem>
              <MenuItem
                onSelect={() => {
                  onMoveTo(card.key, card.statusId, 'bottom')
                }}
              >
                Bottom of column
              </MenuItem>
            </MenuGroup>
            {targets.length > 0 && (
              <MenuGroup label="Move to">
                {targets.map((target) => (
                  <MenuItem
                    key={target.id}
                    onSelect={() => {
                      onMoveTo(card.key, target.id, 'keep')
                    }}
                  >
                    {target.name}
                  </MenuItem>
                ))}
              </MenuGroup>
            )}
          </Menu>
        )
      }
    />
  )
}

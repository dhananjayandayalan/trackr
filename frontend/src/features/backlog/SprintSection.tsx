import { useVirtualizer } from '@tanstack/react-virtual'
import { useId, useRef, useState, type ReactNode } from 'react'
import type { EpicSummary, MemberView } from '@/shared/api/types.ts'
import { Icon } from '@/shared/ui/Icon.tsx'
import { pointsOf, type ListId, type LiveRow } from './backlog-cache.ts'
import { BacklogRow } from './BacklogRow.tsx'
import { InlineCreate } from './InlineCreate.tsx'
import { useAutoScroll, useListDrop } from './useBacklogDnd.ts'
import styles from './SprintSection.module.css'

/** Past this many rows a list renders only what's in view (07-frontend §6). */
export const VIRTUAL_AFTER = 50
const ROW_HEIGHT = 44

interface SprintSectionProps {
  projectKey: string
  listId: ListId
  title: string
  /** dates, goal… under the title */
  meta?: ReactNode
  /** Start / Complete / the sprint menu */
  actions?: ReactNode
  /** all the list's rows (for the counts and point totals) */
  allRows: LiveRow[]
  /** the rows that pass the filters (what's drawn) */
  rows: LiveRow[]
  epics: ReadonlyMap<string, EpicSummary>
  members: ReadonlyMap<string, MemberView>
  canEdit: boolean
}

/** One collapsible list of the backlog screen (00-product §3.6): a sprint, or the backlog itself. */
export function SprintSection(props: SprintSectionProps) {
  const { projectKey, listId, title, meta, actions, allRows, canEdit } = props
  const [open, setOpen] = useState(true)
  const bodyId = useId()
  const points = pointsOf(allRows)

  return (
    <section className={styles.root} aria-label={title}>
      {/* BLOCK 1: the header — the toggle is a real button with aria-expanded */}
      <header className={styles.header}>
        <button
          type="button"
          className={styles.toggle}
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => {
            setOpen(!open)
          }}
        >
          <Icon name={open ? 'chevron-down' : 'chevron-right'} size="sm" />
          <span className={styles.title}>{title}</span>
          <span className={styles.count}>
            {allRows.length} {allRows.length === 1 ? 'issue' : 'issues'}
          </span>
        </button>
        {meta && <span className={styles.meta}>{meta}</span>}
        {/* point totals per status category, coloured like the statuses */}
        <span
          className={styles.points}
          aria-label={`Points: ${points.todo} to do, ${points.inProgress} in progress, ${points.done} done`}
        >
          <span data-category="todo">{points.todo}</span>
          <span data-category="progress">{points.inProgress}</span>
          <span data-category="done">{points.done}</span>
        </span>
        {actions && <span className={styles.actions}>{actions}</span>}
      </header>

      {open && (
        <div id={bodyId}>
          <Rows {...props} />
          {canEdit && <InlineCreate projectKey={projectKey} sprintId={listId} />}
        </div>
      )}
    </section>
  )
}

// BLOCK 2: the rows. Short lists render every row. Long ones get their own scroll box and render only the rows
// in view (+ 8 either side): the rows stay in the grid's flow, and padding above and below stands in for the
// ones not drawn — so subgrid alignment still works, which absolutely positioned rows would break.
// Two elements, on purpose: the padding goes on the list (the grid) inside the scroll box, never on the scroll
// box itself — a border-box can't be smaller than its padding, so 40 000px of padding there would override
// its max-block-size, the box would grow to the whole list, and every row would be "in view".
function Rows({ projectKey, listId, rows, epics, members, canEdit, allRows }: SprintSectionProps) {
  // The virtualiser keeps its state in a mutable object and re-renders us when it scrolls; the React Compiler
  // would memoise what we read from that object and miss the changes. This opts this component out.
  'use no memo'
  const scroller = useRef<HTMLDivElement>(null)
  const list = useRef<HTMLOListElement>(null)
  const over = useListDrop(list, listId)
  const virtual = rows.length > VIRTUAL_AFTER
  useAutoScroll(scroller, virtual)

  // eslint-disable-next-line react-hooks/incompatible-library -- known, and why this component says 'use no memo'
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 8,
    enabled: virtual,
  })

  const row = (item: LiveRow, index?: number) => (
    <BacklogRow
      key={item.key}
      projectKey={projectKey}
      row={item}
      listId={listId}
      epic={item.epicId ? epics.get(item.epicId) : undefined}
      assignee={item.assigneeId ? members.get(item.assigneeId) : undefined}
      canEdit={canEdit}
      {...(index === undefined ? {} : { index })}
    />
  )

  const items = virtual ? virtualizer.getVirtualItems() : []
  const padStart = items[0]?.start ?? 0
  const padEnd = items.length > 0 ? virtualizer.getTotalSize() - (items.at(-1)?.end ?? 0) : 0

  return (
    <div ref={scroller} className={styles.scroller} data-virtual={virtual || undefined}>
      <ol
        ref={list}
        role="list"
        className={styles.rows}
        data-over={over || undefined}
        style={virtual ? { paddingBlock: `${padStart}px ${padEnd}px` } : undefined}
        aria-label={`${rows.length} issues`}
      >
        {rows.length === 0 && (
          <li className={styles.empty}>
            {allRows.length === 0
              ? 'Nothing here yet — drag issues in, or create one.'
              : 'No issues match the filters.'}
          </li>
        )}
        {virtual
          ? items.map((item) => {
              const issue = rows[item.index]
              return issue ? row(issue, item.index) : null
            })
          : rows.map((item) => row(item))}
      </ol>
    </div>
  )
}

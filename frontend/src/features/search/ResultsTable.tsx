import { Link } from '@tanstack/react-router'
import { useVirtualizer } from '@tanstack/react-virtual'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { SearchHit } from '@/shared/api/types.ts'
import { timeAgo } from '@/shared/lib/dates.ts'
import { issueType, priority } from '@/shared/lib/enums.ts'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Lozenge, type LozengeTone } from '@/shared/ui/Lozenge.tsx'
import { PriorityIcon } from '@/shared/ui/PriorityIcon.tsx'
import { TypeIcon } from '@/shared/ui/TypeIcon.tsx'
import { highlightParts } from './highlight.ts'
import type { Sort } from './tql.ts'
import styles from './ResultsTable.module.css'

// BLOCK 1: columns — `sort` is the TQL field its header sorts by (05-search §3: only sortable fields have one)
const columns = [
  { id: 'key', label: 'Key', sort: 'key' },
  { id: 'summary', label: 'Summary', sort: 'summary' },
  { id: 'status', label: 'Status', sort: 'status' },
  { id: 'assignee', label: 'Assignee', sort: 'assignee' },
  { id: 'priority', label: 'Priority', sort: 'priority' },
  { id: 'points', label: 'Points', sort: 'storyPoints' },
  { id: 'sprint', label: 'Sprint', sort: 'sprint' },
  { id: 'updated', label: 'Updated', sort: 'updated' },
] as const

const VIRTUAL_AFTER = 60
const ROW_HEIGHT = 44
const categoryTone: Record<string, LozengeTone> = { todo: 'todo', in_progress: 'progress', done: 'done' }

interface ResultsTableProps {
  rows: SearchHit[]
  sort: Sort | null
  onSort: (sort: Sort) => void
  selected: ReadonlySet<string>
  onSelect: (keys: ReadonlySet<string>) => void
  /** near the end of what's loaded: load the next page */
  onNearEnd: () => void
  /** shows over the rows while a new query replaces them */
  stale?: boolean
  bulkBar?: ReactNode
}

/**
 * The results: a real <table> (headers announce with every cell), the header row sticky while the body scrolls.
 * Long result lists render only the rows in view; spacer rows above and below stand in for the rest, so the
 * table keeps its column layout (absolutely positioned rows would leave the table).
 */
export function ResultsTable({
  rows,
  sort,
  onSort,
  selected,
  onSelect,
  onNearEnd,
  stale,
  bulkBar,
}: ResultsTableProps) {
  // the virtualiser mutates its own state and re-renders us: the React Compiler mustn't memoise reads of it
  'use no memo'
  const scroller = useRef<HTMLDivElement>(null)
  // one "now" per mount: rendering stays pure
  const [now] = useState(Date.now)
  const virtual = rows.length > VIRTUAL_AFTER
  // eslint-disable-next-line react-hooks/incompatible-library -- known, and why this component says 'use no memo'
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 10,
    enabled: virtual,
  })
  const items = virtual ? virtualizer.getVirtualItems() : []
  const lastIndex = virtual ? (items.at(-1)?.index ?? 0) : rows.length - 1

  // BLOCK 2: infinite loading — when the last drawn row is within 10 of the end, ask for the next page
  useEffect(() => {
    if (rows.length > 0 && lastIndex >= rows.length - 10) {
      onNearEnd()
    }
  }, [lastIndex, rows.length, onNearEnd])

  const allSelected = rows.length > 0 && rows.every((row) => selected.has(row.key))
  const someSelected = !allSelected && rows.some((row) => selected.has(row.key))
  const toggle = (key: string, checked: boolean) => {
    const next = new Set(selected)
    if (checked) {
      next.add(key)
    } else {
      next.delete(key)
    }
    onSelect(next)
  }

  const padStart = items[0]?.start ?? 0
  const padEnd = items.length > 0 ? virtualizer.getTotalSize() - (items.at(-1)?.end ?? 0) : 0
  const drawn = virtual
    ? items.map((item) => ({ row: rows[item.index], index: item.index }))
    : rows.map((row, index) => ({ row, index }))

  return (
    <div className={styles.root}>
      {bulkBar}
      <div ref={scroller} className={styles.scroller} data-stale={stale ? true : undefined}>
        <table className={styles.table} aria-rowcount={rows.length + 1}>
          <thead>
            <tr>
              <th scope="col" className={styles.check}>
                <input
                  type="checkbox"
                  aria-label="Select all loaded issues"
                  checked={allSelected}
                  ref={(element) => {
                    if (element) {
                      element.indeterminate = someSelected
                    }
                  }}
                  onChange={(event) => {
                    onSelect(event.target.checked ? new Set(rows.map((row) => row.key)) : new Set())
                  }}
                />
              </th>
              {columns.map((column) => {
                const direction =
                  sort?.field.toLowerCase() === column.sort.toLowerCase() ? sort.direction : undefined
                const active = direction !== undefined
                // BLOCK 3: aria-sort on the header cell; the button inside does the sorting
                return (
                  <th
                    key={column.id}
                    scope="col"
                    aria-sort={
                      direction === 'asc' ? 'ascending' : direction === 'desc' ? 'descending' : 'none'
                    }
                    data-col={column.id}
                  >
                    <button
                      type="button"
                      className={styles.sort}
                      onClick={() => {
                        onSort({
                          field: column.sort,
                          // first click: newest/highest first for dates and priority, A→Z for the rest
                          direction: active
                            ? direction === 'asc'
                              ? 'desc'
                              : 'asc'
                            : ['updated', 'priority', 'storyPoints'].includes(column.sort)
                              ? 'desc'
                              : 'asc',
                        })
                      }}
                    >
                      {column.label}
                      <Icon
                        name={direction === 'asc' ? 'chevron-up' : 'chevron-down'}
                        size="sm"
                        className={styles.sortIcon}
                        data-active={active || undefined}
                      />
                    </button>
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {virtual && padStart > 0 && (
              <tr aria-hidden="true" className={styles.spacer}>
                <td colSpan={columns.length + 1} style={{ blockSize: `${String(padStart)}px` }} />
              </tr>
            )}
            {drawn.map(({ row, index }) =>
              row ? (
                <tr
                  key={row.id}
                  aria-rowindex={index + 2}
                  className={styles.row}
                  ref={virtual ? virtualizer.measureElement : undefined}
                  data-index={index}
                >
                  <td className={styles.check}>
                    <input
                      type="checkbox"
                      aria-label={`Select ${row.key}`}
                      checked={selected.has(row.key)}
                      onChange={(event) => {
                        toggle(row.key, event.target.checked)
                      }}
                    />
                  </td>
                  <td data-col="key">
                    <span className={styles.keyCell}>
                      <TypeIcon type={issueType(row.type)} />
                      <Link
                        to="/p/$projectKey/issues/$issueKey"
                        params={{ projectKey: row.projectKey, issueKey: row.key }}
                        className={styles.key}
                      >
                        {row.key}
                      </Link>
                    </span>
                  </td>
                  <td data-col="summary" className={styles.summary}>
                    <Summary row={row} />
                  </td>
                  <td data-col="status">
                    <Lozenge tone={categoryTone[row.statusCategory] ?? 'todo'}>
                      {row.statusName ?? '—'}
                    </Lozenge>
                  </td>
                  <td data-col="assignee" className={styles.muted}>
                    {row.assigneeName ?? 'Unassigned'}
                  </td>
                  <td data-col="priority">
                    <span className={styles.priority}>
                      <PriorityIcon priority={priority(row.priority)} />
                      {row.priority}
                    </span>
                  </td>
                  <td data-col="points" className={styles.number}>
                    {row.storyPoints ?? '—'}
                  </td>
                  <td data-col="sprint" className={styles.muted}>
                    {row.sprintName ?? 'Backlog'}
                  </td>
                  <td data-col="updated" className={styles.muted}>
                    <time dateTime={row.updatedAt}>{timeAgo(row.updatedAt, now)}</time>
                  </td>
                </tr>
              ) : null,
            )}
            {virtual && padEnd > 0 && (
              <tr aria-hidden="true" className={styles.spacer}>
                <td colSpan={columns.length + 1} style={{ blockSize: `${String(padEnd)}px` }} />
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/** The summary, with the words that matched marked (text search only). */
function Summary({ row }: { row: SearchHit }) {
  const fragment = row.highlights.summary?.[0]
  if (!fragment) {
    return <>{row.summary}</>
  }
  return (
    <>
      {highlightParts(fragment).map((part, index) =>
        part.marked ? (
          <mark key={`${String(index)}-${part.text}`}>{part.text}</mark>
        ) : (
          <span key={`${String(index)}-${part.text}`}>{part.text}</span>
        ),
      )}
    </>
  )
}

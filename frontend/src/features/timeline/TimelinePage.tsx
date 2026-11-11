import { useSuspenseQuery } from '@tanstack/react-query'
import { getRouteApi, Link } from '@tanstack/react-router'
import { useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from 'react'
import type { TimelineChild, TimelineEpic } from '@/shared/api/types.ts'
import { dayLabel, isoDay } from '@/shared/lib/dates.ts'
import { issueType } from '@/shared/lib/enums.ts'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Progress } from '@/shared/ui/Progress.tsx'
import { TypeIcon } from '@/shared/ui/TypeIcon.tsx'
import { barSpan, gridFor, movedDue, todayPercent, weekStarts, type Grid } from './grid.ts'
import { timelineQuery, useMoveDates } from './queries.ts'
import styles from './TimelinePage.module.css'

const route = getRouteApi('/_authed/p/$projectKey/timeline')

/**
 * Timeline (00-product §3.9): epics as bars over weeks, expandable to their issues. The layout is CSS grid —
 * one column for names, one per week — and every bar is placed with `grid-column: var(--start) / var(--end)`,
 * custom properties set from the data. The browser does all the positioning; we only do date → column math.
 */
export function TimelinePage() {
  const { projectKey } = route.useParams()
  const { data } = useSuspenseQuery(timelineQuery(projectKey))
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())
  const [today] = useState(() => isoDay())
  const move = useMoveDates(projectKey)

  // BLOCK 1: the grid covers every date on the page, and today
  const grid = gridFor(
    data.epics.flatMap((epic) => [
      epic.startDate,
      epic.dueDate,
      ...epic.children.flatMap((child) => [child.startDate, child.dueDate]),
    ]),
    today,
  )
  // --today is a fraction (0–1) of the week columns' width; CSS multiplies it, it can't divide by a percentage
  const style = { '--weeks': grid.weeks, '--today': todayPercent(grid, today) / 100 } as CSSProperties

  const toggle = (key: string) => {
    const next = new Set(open)
    if (next.has(key)) {
      next.delete(key)
    } else {
      next.add(key)
    }
    setOpen(next)
  }

  const moveDue = (row: TimelineEpic | TimelineChild, weeks: number) => {
    if (weeks !== 0 && row.dueDate) {
      move.mutate({
        issueKey: row.key,
        version: row.version,
        dates: { dueDate: movedDue(row.dueDate, weeks, row.startDate) },
      })
    }
  }

  if (data.epics.length === 0) {
    return (
      <div className={styles.page}>
        <h1>Timeline</h1>
        <EmptyState icon="timeline" title="No epics yet">
          Create an epic and give it a start and a due date to see it here.
        </EmptyState>
      </div>
    )
  }

  return (
    <div className={styles.page}>
      <header>
        <p className={styles.eyebrow}>{projectKey}</p>
        <h1>Timeline</h1>
      </header>
      <div className={styles.scroller}>
        <div className={styles.timeline} style={style}>
          {/* BLOCK 2: week header — the Monday of each column */}
          <div className={styles.row} data-head>
            <span className={styles.label}>Epic</span>
            {weekStarts(grid).map((week, i) => (
              <span key={week} className={styles.week} style={{ gridColumn: i + 2 }} data-week>
                {dayLabel(week)}
              </span>
            ))}
          </div>
          {/* the today line: a percentage of the week columns' width, from a custom property */}
          <div className={styles.today} aria-hidden="true" />

          <ul role="list" className={styles.rows}>
            {data.epics.map((epic) => (
              <li key={epic.id}>
                <div className={styles.row} data-epic>
                  <span className={styles.label}>
                    <button
                      type="button"
                      className={styles.expand}
                      aria-expanded={open.has(epic.key)}
                      aria-label={`${open.has(epic.key) ? 'Collapse' : 'Expand'} ${epic.key}`}
                      disabled={epic.children.length === 0}
                      onClick={() => {
                        toggle(epic.key)
                      }}
                    >
                      <Icon name={open.has(epic.key) ? 'chevron-down' : 'chevron-right'} size="sm" />
                    </button>
                    <TypeIcon type="epic" />
                    <Link
                      to="/p/$projectKey/issues/$issueKey"
                      params={{ projectKey, issueKey: epic.key }}
                      className={styles.key}
                    >
                      {epic.key}
                    </Link>
                    <span className={styles.summary}>{epic.summary}</span>
                    <span className={styles.progress}>
                      <Progress value={epic.progressPct} label={`${epic.key} progress`} />
                    </span>
                  </span>
                  <Bar grid={grid} row={epic} kind="epic" onMove={moveDue} />
                </div>
                {open.has(epic.key) && (
                  <ul role="list" aria-label={`Issues of ${epic.key}`}>
                    {epic.children.map((child) => (
                      <li key={child.id} className={styles.row} data-child>
                        <span className={styles.label}>
                          <TypeIcon type={issueType(child.type)} />
                          <Link
                            to="/p/$projectKey/issues/$issueKey"
                            params={{ projectKey, issueKey: child.key }}
                            className={styles.key}
                          >
                            {child.key}
                          </Link>
                          <span className={styles.summary}>{child.summary}</span>
                        </span>
                        <Bar grid={grid} row={child} kind="child" onMove={moveDue} />
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  )
}

/**
 * One bar, with a handle on its end. Drag: pointer events with setPointerCapture — once captured, every move and
 * the release come to the handle even when the pointer leaves it (or the window). Keyboard: ←/→ move a week.
 */
function Bar({
  grid,
  row,
  kind,
  onMove,
}: {
  grid: Grid
  row: TimelineEpic | TimelineChild
  kind: 'epic' | 'child'
  onMove: (row: TimelineEpic | TimelineChild, weeks: number) => void
}) {
  const [drag, setDrag] = useState<{ x: number; width: number; weeks: number } | null>(null)
  const bar = useRef<HTMLDivElement>(null)
  const span = barSpan(grid, row.startDate, row.dueDate)
  if (!span) {
    return (
      <span className={styles.undated} style={{ gridColumn: '2 / -1' }}>
        No dates
      </span>
    )
  }
  const weeks = drag?.weeks ?? 0
  const style = {
    '--start': span.start,
    '--end': Math.max(span.start + 1, span.end + weeks),
  } as CSSProperties
  const done = row.statusCategory === 'done'
  const range = `${row.startDate ? dayLabel(row.startDate) : '…'} – ${row.dueDate ? dayLabel(row.dueDate) : '…'}`

  // BLOCK 3: drag the end handle; one week column = the bar's width ÷ the columns it spans
  const onPointerDown = (event: PointerEvent<HTMLButtonElement>) => {
    if (!row.dueDate || !bar.current) {
      return
    }
    event.currentTarget.setPointerCapture(event.pointerId)
    const width = bar.current.getBoundingClientRect().width / Math.max(1, span.end - span.start)
    setDrag({ x: event.clientX, width, weeks: 0 })
  }
  const onPointerMove = (event: PointerEvent<HTMLButtonElement>) => {
    if (drag) {
      setDrag({ ...drag, weeks: Math.round((event.clientX - drag.x) / drag.width) })
    }
  }
  const onPointerUp = (event: PointerEvent<HTMLButtonElement>) => {
    if (drag) {
      event.currentTarget.releasePointerCapture(event.pointerId)
      onMove(row, drag.weeks)
      setDrag(null)
    }
  }
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      onMove(row, event.key === 'ArrowRight' ? 1 : -1)
    }
  }

  return (
    <div
      ref={bar}
      className={styles.bar}
      data-kind={kind}
      data-done={done || undefined}
      data-dragging={drag ? true : undefined}
      style={style}
      title={`${row.key}: ${range}`}
    >
      <span className={styles.barText}>{range}</span>
      {row.dueDate && (
        <button
          type="button"
          className={styles.handle}
          aria-label={`Due date of ${row.key}: ${dayLabel(row.dueDate)}. Arrow keys move it by a week.`}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={() => {
            setDrag(null)
          }}
          onKeyDown={onKeyDown}
        />
      )}
    </div>
  )
}

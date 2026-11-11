/**
 * Date math for the timeline: calendar days ("2026-11-09") ↔ week columns of a CSS grid. Days are counted in
 * UTC (Date.UTC), never local time: a day is always 86 400 000 ms there, so a daylight-saving change in the
 * middle of the timeline can't shift a bar by an hour and round it into the wrong week.
 */

const DAY = 86_400_000

// BLOCK 1: calendar days as numbers
export function toDay(iso: string): number {
  const [year = 1970, month = 1, date = 1] = iso.split('-').map(Number)
  return Date.UTC(year, month - 1, date) / DAY
}

export function fromDay(day: number): string {
  return new Date(day * DAY).toISOString().slice(0, 10)
}

export function addDays(iso: string, days: number): string {
  return fromDay(toDay(iso) + days)
}

/** The Monday on or before the day. 1970-01-01 was a Thursday, so day 0 + 4 is a Monday. */
export function mondayOf(iso: string): string {
  const day = toDay(iso)
  return fromDay(day - ((day - 4 + 7 * 1000) % 7))
}

// BLOCK 2: the grid — whole weeks from a week before the earliest date (or today) to two after the latest
export interface Grid {
  /** the Monday of the first column */
  start: string
  weeks: number
}

export function gridFor(dates: readonly (string | null | undefined)[], today: string, minWeeks = 12): Grid {
  const days = [today, ...dates].filter((d): d is string => Boolean(d)).map(toDay)
  const first = toDay(mondayOf(fromDay(Math.min(...days)))) - 7
  const last = Math.max(...days) + 14
  return { start: fromDay(first), weeks: Math.max(minWeeks, Math.ceil((last - first + 1) / 7)) }
}

/** 0 for the first week column; clamped to the grid. */
export function weekIndex(grid: Grid, iso: string): number {
  const index = Math.floor((toDay(iso) - toDay(grid.start)) / 7)
  return Math.min(grid.weeks - 1, Math.max(0, index))
}

/**
 * Grid lines for a bar: `grid-column: start / end`. Line 1 is before the name column, so week 0 starts at line 2.
 * Only a due date: a one-week bar ending on it. Only a start: one week from it. Neither: no bar.
 */
export function barSpan(
  grid: Grid,
  startDate: string | null | undefined,
  dueDate: string | null | undefined,
): { start: number; end: number } | null {
  if (!startDate && !dueDate) {
    return null
  }
  const from = startDate ?? addDays(dueDate ?? '', -6)
  const to = dueDate ?? addDays(from, 6)
  const start = weekIndex(grid, from) + 2
  return { start, end: Math.max(start + 1, weekIndex(grid, to) + 3) }
}

/** Where today's line goes, as a percentage of the week columns' width (the middle of today). */
export function todayPercent(grid: Grid, today: string): number {
  return ((toDay(today) - toDay(grid.start) + 0.5) / (grid.weeks * 7)) * 100
}

/** Week labels: the Monday of each column. */
export function weekStarts(grid: Grid): string[] {
  return Array.from({ length: grid.weeks }, (_, i) => addDays(grid.start, i * 7))
}

/** A drag of the end handle by `weeks` columns; never before the start date. */
export function movedDue(dueDate: string, weeks: number, startDate?: string | null): string {
  const moved = addDays(dueDate, weeks * 7)
  return startDate && toDay(moved) < toDay(startDate) ? startDate : moved
}

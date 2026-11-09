// Formatting with the browser's Intl API: the user's language and conventions, no date library.
// Formatters are built once; constructing one is the expensive part.

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
const day = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' })

const steps: [Intl.RelativeTimeFormatUnit, number][] = [
  ['second', 60],
  ['minute', 60],
  ['hour', 24],
  ['day', 7],
  ['week', 4.35],
  ['month', 12],
  ['year', Number.POSITIVE_INFINITY],
]

/** "3 minutes ago", "yesterday", "2 weeks ago" — `now` is a parameter so tests can fix it. */
export function timeAgo(iso: string, now: number = Date.now()): string {
  let amount = (new Date(iso).getTime() - now) / 1000
  for (const [unit, size] of steps) {
    if (Math.abs(amount) < size) {
      return relative.format(Math.round(amount), unit)
    }
    amount /= size
  }
  return relative.format(Math.round(amount), 'year')
}

/** "9 Nov" */
export function shortDate(iso: string): string {
  return day.format(new Date(iso))
}

// Calendar days (sprint dates are "2026-11-09", no time, no zone). `new Date("2026-11-09")` would read that as
// midnight UTC — the evening before, west of Greenwich — so build the local date from its parts.
function localDay(isoDate: string): Date {
  const [year = 0, month = 1, date = 1] = isoDate.split('-').map(Number)
  return new Date(year, month - 1, date)
}

/** "9 Nov" for a calendar day */
export function dayLabel(isoDate: string): string {
  return day.format(localDay(isoDate))
}

/** Whole days from today to that day: 0 today, negative once it's past. */
export function daysUntil(isoDate: string, now: Date = new Date()): number {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.round((localDay(isoDate).getTime() - today.getTime()) / 86_400_000)
}

/** Today, or today plus some days, as "2026-11-09" in the user's time zone (a date input's value). */
export function isoDay(offsetDays = 0, now: Date = new Date()): string {
  const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offsetDays)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

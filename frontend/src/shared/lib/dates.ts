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

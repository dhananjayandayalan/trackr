// Time tracking speaks in minutes on the wire (originalEstimateMin, worklog.minutes) and "1h 30m" to people.
// A working day is 8 hours, as in most trackers: "2d" of work is 16h, not 48h.

const MINUTES = { w: 5 * 8 * 60, d: 8 * 60, h: 60, m: 1 } as const

/** 90 → "1h 30m", 480 → "1d", 0 → "0m". */
export function formatMinutes(total: number): string {
  if (total <= 0) {
    return '0m'
  }
  let rest = Math.round(total)
  const parts: string[] = []
  for (const [unit, size] of Object.entries(MINUTES)) {
    const amount = Math.floor(rest / size)
    if (amount > 0) {
      parts.push(`${amount}${unit}`)
      rest -= amount * size
    }
  }
  return parts.join(' ')
}

/**
 * "1h 30m", "90m", "1.5h", "2d", or a bare number of minutes → minutes; null when it isn't a duration.
 * Every part needs a unit except a lone number, so "1 30" is refused rather than guessed.
 */
export function parseDuration(input: string): number | null {
  const text = input.trim().toLowerCase()
  if (/^\d+$/.test(text)) {
    return Number(text) > 0 ? Number(text) : null
  }
  const part = /(\d+(?:\.\d+)?)\s*([wdhm])/g
  let total = 0
  let consumed = ''
  for (const match of text.matchAll(part)) {
    total += Number(match[1]) * MINUTES[match[2] as keyof typeof MINUTES]
    consumed += match[0]
  }
  // everything typed must have been understood (spaces aside)
  if (consumed.replace(/\s/g, '') !== text.replace(/\s/g, '') || total <= 0) {
    return null
  }
  return Math.round(total)
}

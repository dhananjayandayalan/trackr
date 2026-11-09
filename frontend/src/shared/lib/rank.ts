/**
 * Fractional indexing — a line-by-line port of the backend's `Rank.java` (07-frontend §5). Both must give the
 * same answer for the same input: `rank.test.ts` runs the vectors in docs/spec/fixtures/rank-vectors.json,
 * which the Java tests wrote. If a vector fails here, one of the two implementations is wrong — find which.
 *
 * A rank is a base-62 fraction written without the leading "0.": "V" is 31/62, about 0.5, and "VV" is a bit
 * more than that. Comparing two ranks as strings compares the fractions, because the digits 0-9A-Za-z are in
 * ASCII order — so `a < b` on two ranks is right, and `localeCompare` is wrong (it would put "a" before "B").
 *
 * The browser uses it for one thing: a provisional rank, so an optimistically moved card sorts where it was
 * dropped. The server computes the real one from the neighbours' current ranks, and its answer replaces ours.
 */

// BLOCK 1: alphabet and helpers
export const DIGITS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
const BASE = DIGITS.length
const ZERO = DIGITS.charAt(0)
const LAST = DIGITS.charAt(BASE - 1)

function digitValue(digit: string): number {
  const value = DIGITS.indexOf(digit)
  if (value < 0 || digit.length !== 1) {
    throw new RangeError(`not a rank digit: '${digit}'`)
  }
  return value
}

function digit(value: number): string {
  return DIGITS.charAt(value)
}

// a trailing '0' adds nothing to the fraction ("V0" == "V"), and "…0" has no room directly below it
function requireValid(rank: string) {
  if (rank === '') {
    throw new RangeError('a rank cannot be empty')
  }
  for (const char of rank) {
    digitValue(char)
  }
  if (rank.endsWith(ZERO)) {
    throw new RangeError(`a rank cannot end in '0': ${rank}`)
  }
}

/** Rank order: plain code-unit comparison, as Postgres does with COLLATE "C". For Array.sort. */
export function compareRanks(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}

// BLOCK 2: midpoint(a, b)
/** A string strictly between a and b. a may be "" (= 0), b may be null (= 1). Neither may end in '0'. */
function midpoint(a: string, b: string | null): string {
  if (b !== null) {
    // the shared prefix stays; "" counts as padded with zeros, so midpoint("", "05") keeps the "0"
    let n = 0
    while (n < b.length && (n < a.length ? a.charAt(n) : ZERO) === b.charAt(n)) {
      n++
    }
    if (n > 0) {
      return b.slice(0, n) + midpoint(a.slice(Math.min(n, a.length)), b.slice(n))
    }
  }
  // the first digits differ
  const digitA = a === '' ? 0 : digitValue(a.charAt(0))
  const digitB = b === null ? BASE : digitValue(b.charAt(0))
  if (digitB - digitA > 1) {
    // room for a digit in between: take the middle one ("1" and "9" → "5"); Java's int division truncates
    return digit(Math.trunc((digitA + digitB + 1) / 2))
  }
  // neighbouring digits, like "4…" and "5…"
  if (b !== null && b.length > 1) {
    // "5" alone sorts before "5x" and after anything starting with "4"
    return b.slice(0, 1)
  }
  // b is "5" or +∞: keep a's first digit and find room after the rest of a ("49" and "5" → "4" + "9V")
  return digit(digitA) + midpoint(a === '' ? '' : a.slice(1), null)
}

// BLOCK 3: between(before, after)
/**
 * The rank for a new position: between(null, null) for the first item of an empty list, between(last, null)
 * to append, between(null, first) to prepend, both to insert. Appending and prepending step by one digit
 * instead of halving the gap, so the ends of a list grow slowly.
 */
export function between(before: string | null, after: string | null): string {
  if (before !== null) {
    requireValid(before)
  }
  if (after !== null) {
    requireValid(after)
  }
  if (before === null) {
    // the middle digit for an empty list, room on both sides; else one step below the first
    return after === null ? midpoint('', null) : rankBefore(after)
  }
  if (after === null) {
    return rankAfter(before)
  }
  if (before >= after) {
    throw new RangeError(`before must sort below after: ${before} >= ${after}`)
  }
  return midpoint(before, after)
}

// the first digit that can go up by one, with everything after it dropped: "V" → "W", "zz3" → "zz4", "z" → "zV"
function rankAfter(rank: string): string {
  for (let i = 0; i < rank.length; i++) {
    if (rank.charAt(i) !== LAST) {
      return rank.slice(0, i) + digit(digitValue(rank.charAt(i)) + 1)
    }
  }
  return rank + digit(BASE / 2)
}

// the first digit that can go down by one without becoming a trailing '0': "V" → "U", "1V" → "1U", "1" → "0V"
function rankBefore(rank: string): string {
  for (let i = 0; i < rank.length; i++) {
    if (digitValue(rank.charAt(i)) > 1) {
      return rank.slice(0, i) + digit(digitValue(rank.charAt(i)) - 1)
    }
  }
  // only 0s and 1s, ending in '1': replace that last '1' with "0V"
  return midpoint('', rank)
}

// BLOCK 4: nBetween(before, after, n)
/** n ranks between before and after, in order, spread by bisection so none is much longer than the others. */
export function nBetween(before: string | null, after: string | null, n: number): string[] {
  if (n < 0 || !Number.isInteger(n)) {
    throw new RangeError(`n must be a whole number, not negative: ${n}`)
  }
  const ranks: string[] = []
  fill(before, after, n, ranks)
  return ranks
}

// the middle one first, then the halves on each side of it
function fill(before: string | null, after: string | null, n: number, out: string[]) {
  if (n === 0) {
    return
  }
  const middle = between(before, after)
  const left = Math.trunc((n - 1) / 2)
  fill(before, middle, left, out)
  out.push(middle)
  fill(middle, after, n - 1 - left, out)
}

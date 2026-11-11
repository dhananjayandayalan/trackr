/**
 * Fuzzy matching for the palette's own items ("gb" finds "Go to board"): the query's letters must appear in the
 * text in order, not necessarily together. The score rewards what people mean: matches at the start of a word,
 * and letters that follow each other. -1 = no match. Server results (issues) are matched by the server.
 */
export function fuzzyScore(query: string, text: string): number {
  const q = query.trim().toLowerCase()
  if (q === '') {
    return 0
  }
  const t = text.toLowerCase()
  let score = 0
  let from = 0
  let previous = -2
  for (const char of q) {
    if (char === ' ') {
      continue
    }
    const at = t.indexOf(char, from)
    if (at < 0) {
      return -1
    }
    const wordStart = at === 0 || /[\s/·-]/.test(t[at - 1] ?? '')
    score += 1 + (wordStart ? 3 : 0) + (at === previous + 1 ? 2 : 0)
    previous = at
    from = at + 1
  }
  // shorter texts win ties: "Board" before "Board settings"
  return score - t.length / 100
}

/** The items that match, best first. */
export function fuzzyFilter<T>(query: string, items: readonly T[], text: (item: T) => string): T[] {
  return items
    .map((item) => ({ item, score: fuzzyScore(query, text(item)) }))
    .filter((entry) => entry.score >= 0)
    .sort((a, b) => b.score - a.score)
    .map((entry) => entry.item)
}

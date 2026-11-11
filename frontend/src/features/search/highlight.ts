/**
 * Search highlights arrive as HTML fragments: the text HTML-escaped by the server, the matched words wrapped in
 * <mark>…</mark> (05-search §3). Instead of injecting that HTML, it is split into plain parts here and React
 * renders them as text — nothing from the index can ever become markup.
 */
export interface Part {
  text: string
  marked: boolean
}

const entities: Record<string, string> = {
  '&lt;': '<',
  '&gt;': '>',
  '&amp;': '&',
  '&quot;': '"',
  '&#39;': "'",
  '&#x27;': "'",
}

function unescape(text: string): string {
  return text.replace(/&(?:lt|gt|amp|quot|#39|#x27);/g, (entity) => entities[entity] ?? entity)
}

export function highlightParts(fragment: string): Part[] {
  const parts: Part[] = []
  for (const [index, piece] of fragment.split(/<\/?mark>/).entries()) {
    if (piece !== '') {
      // split alternates: outside, inside, outside, … — odd indexes were between <mark> and </mark>
      parts.push({ text: unescape(piece), marked: index % 2 === 1 })
    }
  }
  return parts
}

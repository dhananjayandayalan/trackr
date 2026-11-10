/**
 * The composer is a plain textarea; the api stores HTML (03-api §3.5). These two functions translate:
 * text + the people picked from the mention list → HTML for the api, and stored HTML → text + mentions for
 * editing. Everything the user typed is escaped, so the only tags in the result are the ones we write:
 * <p>, <br> and the mention <span>. The api sanitises again anyway — never trust the client, even our own.
 */

export interface Mention {
  id: string
  name: string
}

const ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

function escape(text: string): string {
  return text.replace(/[&<>"']/g, (char) => ESCAPES[char] ?? char)
}

/** Blank lines separate paragraphs, a single newline is a <br>, and "@Name" of a picked person becomes a mention. */
export function toCommentHtml(text: string, mentions: readonly Mention[]): string {
  // longest names first: "@Ada Lovelace" must win over a picked "@Ada"
  const people = [...new Map(mentions.map((m) => [m.id, m])).values()].sort(
    (a, b) => b.name.length - a.name.length,
  )
  return text
    .trim()
    .split(/\n\s*\n/)
    .map((paragraph) => {
      let html = escape(paragraph.trim())
      for (const person of people) {
        // a placeholder first, so a later (shorter) name can't match inside a span we already wrote
        html = html.split(escape(`@${person.name}`)).join(`\u0000${person.id}\u0000`)
      }
      for (const person of people) {
        html = html
          .split(`\u0000${person.id}\u0000`)
          .join(`<span data-mention-id="${person.id}">@${escape(person.name)}</span>`)
      }
      return `<p>${html.replace(/\n/g, '<br>')}</p>`
    })
    .join('')
}

/** Back the other way, for editing a comment: paragraphs → blank lines, <br> → newline, spans → "@Name". */
export function fromCommentHtml(html: string): { text: string; mentions: Mention[] } {
  const doc = new DOMParser().parseFromString(`<body>${html}</body>`, 'text/html')
  const mentions: Mention[] = []
  for (const span of doc.querySelectorAll<HTMLElement>('span[data-mention-id]')) {
    const id = span.dataset.mentionId
    const name = span.textContent.replace(/^@/, '')
    if (id && name && !mentions.some((m) => m.id === id)) {
      mentions.push({ id, name })
    }
  }
  for (const br of doc.querySelectorAll('br')) {
    br.replaceWith('\n')
  }
  const blocks = [...doc.body.children].filter((child) => child.tagName !== 'BR')
  const text =
    blocks.length > 0 ? blocks.map((block) => block.textContent).join('\n\n') : doc.body.textContent
  return { text: text.trim(), mentions }
}

/** The "@query" being typed right before the caret, if any: what the mention list filters by. */
export function mentionQueryAt(text: string, caret: number): { query: string; start: number } | null {
  const match = /(^|\s)@([\p{L}\p{N}._-]{0,30})$/u.exec(text.slice(0, caret))
  if (!match) {
    return null
  }
  const query = match[2] ?? ''
  return { query, start: caret - query.length - 1 }
}

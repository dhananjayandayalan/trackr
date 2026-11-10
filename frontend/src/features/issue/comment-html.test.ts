import { describe, expect, it } from 'vitest'
import { fromCommentHtml, mentionQueryAt, toCommentHtml } from './comment-html.ts'

const ada = { id: '0a48c856-2921-9312-ccd1-780e11c135b5', name: 'Ada Lovelace' }
const adaShort = { id: '11111111-2222-3333-4444-555555555555', name: 'Ada' }

describe('comment html', () => {
  it('escapes everything typed and keeps paragraphs and line breaks', () => {
    expect(toCommentHtml('  <b>bold</b> & "quotes"\nnext line\n\nsecond  ', [])).toBe(
      '<p>&lt;b&gt;bold&lt;/b&gt; &amp; &quot;quotes&quot;<br>next line</p><p>second</p>',
    )
  })

  it('turns picked people into mention spans, longest name first', () => {
    expect(toCommentHtml('@Ada Lovelace and @Ada, see this', [adaShort, ada])).toBe(
      `<p><span data-mention-id="${ada.id}">@Ada Lovelace</span> and ` +
        `<span data-mention-id="${adaShort.id}">@Ada</span>, see this</p>`,
    )
  })

  it('reads stored html back into text and mentions', () => {
    const html = toCommentHtml('Hi @Ada Lovelace\nthanks\n\nbye', [ada])
    expect(fromCommentHtml(html)).toEqual({ text: 'Hi @Ada Lovelace\nthanks\n\nbye', mentions: [ada] })
  })

  it('finds the @query right before the caret', () => {
    expect(mentionQueryAt('hello @ad', 9)).toEqual({ query: 'ad', start: 6 })
    expect(mentionQueryAt('@', 1)).toEqual({ query: '', start: 0 })
    // an e-mail address is not a mention
    expect(mentionQueryAt('mail ada@trakr', 14)).toBeNull()
    expect(mentionQueryAt('hello @ada and more', 19)).toBeNull()
  })
})

import { afterEach, describe, expect, it, vi } from 'vitest'
import { createKeyboard } from './keyboard.ts'

function press(key: string, init: KeyboardEventInit & { target?: EventTarget } = {}): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key, cancelable: true, ...init })
  if (init.target) {
    Object.defineProperty(event, 'target', { value: init.target })
  }
  return event
}

describe('keyboard', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('runs a single-key binding and prevents the default', () => {
    const keys = createKeyboard()
    const run = vi.fn()
    keys.add({ keys: 'c', run })
    const event = press('c')

    expect(keys.handle(event)).toBe(true)
    expect(run).toHaveBeenCalledOnce()
    expect(event.defaultPrevented).toBe(true)
  })

  it('ignores keys typed into fields and editable content, unless the binding allows it', () => {
    const keys = createKeyboard()
    const create = vi.fn()
    const palette = vi.fn()
    keys.add({ keys: 'c', run: create })
    keys.add({ keys: 'mod+k', run: palette, whenTyping: true })
    const input = document.body.appendChild(document.createElement('input'))
    const editor = document.body.appendChild(document.createElement('div'))
    editor.setAttribute('contenteditable', 'true')

    keys.handle(press('c', { target: input }))
    keys.handle(press('c', { target: editor }))
    keys.handle(press('k', { ctrlKey: true, target: input }))

    expect(create).not.toHaveBeenCalled()
    expect(palette).toHaveBeenCalledOnce()
  })

  it('needs no modifier for plain keys: Ctrl+C stays copy', () => {
    const keys = createKeyboard()
    const run = vi.fn()
    keys.add({ keys: 'c', run })

    expect(keys.handle(press('c', { ctrlKey: true }))).toBe(false)
    expect(keys.handle(press('c', { altKey: true }))).toBe(false)
    expect(run).not.toHaveBeenCalled()
  })

  it('runs a sequence typed within the timeout, and not after it', () => {
    let now = 0
    const keys = createKeyboard({ timeoutMs: 1000, now: () => now })
    const board = vi.fn()
    keys.add({ keys: 'g b', run: board })

    keys.handle(press('g'))
    now = 500
    keys.handle(press('b'))
    expect(board).toHaveBeenCalledOnce()

    keys.handle(press('g'))
    now = 2000
    keys.handle(press('b'))
    expect(board).toHaveBeenCalledOnce()
  })

  it('falls back to the key alone when it does not continue the sequence', () => {
    const keys = createKeyboard()
    const backlog = vi.fn()
    const create = vi.fn()
    keys.add({ keys: 'g k', run: backlog })
    keys.add({ keys: 'c', run: create })

    keys.handle(press('g'))
    keys.handle(press('c'))

    expect(create).toHaveBeenCalledOnce()
    expect(backlog).not.toHaveBeenCalled()
  })

  it('does nothing while a modal dialog is open, except modifier shortcuts', () => {
    const keys = createKeyboard()
    const create = vi.fn()
    const palette = vi.fn()
    keys.add({ keys: 'c', run: create })
    keys.add({ keys: 'mod+k', run: palette })
    document.body.appendChild(document.createElement('dialog')).setAttribute('open', '')

    keys.handle(press('c'))
    keys.handle(press('k', { metaKey: true }))

    expect(create).not.toHaveBeenCalled()
    expect(palette).toHaveBeenCalledOnce()
  })

  it('lets the newest registration win, and forgets removed ones', () => {
    const keys = createKeyboard()
    const app = vi.fn()
    const page = vi.fn()
    keys.add({ keys: 'c', run: app })
    const remove = keys.add({ keys: 'c', run: page })

    keys.handle(press('c'))
    remove()
    keys.handle(press('c'))

    expect(page).toHaveBeenCalledOnce()
    expect(app).toHaveBeenCalledOnce()
  })
})

import { useEffect, useEffectEvent } from 'react'

/**
 * Keyboard-first (00-product §3.13): one `keydown` listener on the document for every shortcut in the app.
 * Screens register what they handle while they're mounted; the listener decides what a key press means.
 *
 * - Never while typing: a `c` in a comment is a letter, not "create". Text fields, selects, and
 *   contenteditable (the description editor) are left alone — except for bindings marked `whenTyping`
 *   (Ctrl/Cmd+K opens the palette from anywhere).
 * - Never behind a modal dialog: the page under it is inert.
 * - Sequences: `g b` is `g`, then `b` within a second. While a sequence is half typed, its first key does
 *   nothing on its own.
 */

// BLOCK 1: bindings — `keys` is "c", "/", "g b" or "mod+k" (mod = Ctrl, or Cmd on a Mac)
export interface Binding {
  keys: string
  run: (event: KeyboardEvent) => void
  whenTyping?: boolean
}

export interface KeyboardOptions {
  timeoutMs?: number
  now?: () => number
}

/** The token for one key press: "g", "/", "mod+k". Shift is part of the character ("?"), not a modifier. */
export function tokenOf(event: KeyboardEvent): string | null {
  if (event.altKey || ['Control', 'Meta', 'Shift', 'Alt'].includes(event.key)) {
    return null
  }
  const key = event.key.length === 1 ? event.key.toLowerCase() : event.key
  return event.ctrlKey || event.metaKey ? `mod+${key}` : key
}

export function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false
  }
  if (target.isContentEditable || target.closest('[contenteditable]:not([contenteditable="false"])')) {
    return true
  }
  if (target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement) {
    return true
  }
  // buttons, checkboxes and radios are inputs too, but typing a letter on them does nothing
  return (
    target instanceof HTMLInputElement &&
    !['button', 'checkbox', 'radio', 'submit', 'reset'].includes(target.type)
  )
}

// BLOCK 2: the keymap — pure apart from the document check, so tests can drive it with plain events and a clock
export function createKeyboard({ timeoutMs = 1000, now = () => Date.now() }: KeyboardOptions = {}) {
  const bindings = new Set<Binding>()
  let pending: string[] = []
  let lastAt = 0

  function find(keys: string, typing: boolean): Binding | undefined {
    // the newest registration wins: a page's own `c` can shadow the app's
    return [...bindings].reverse().find((binding) => binding.keys === keys && (!typing || binding.whenTyping))
  }

  function isPrefix(sequence: string, typing: boolean): boolean {
    return [...bindings].some(
      (binding) => binding.keys.startsWith(`${sequence} `) && (!typing || binding.whenTyping),
    )
  }

  /** @returns whether the press was a shortcut (and its default action was prevented) */
  function handle(event: KeyboardEvent): boolean {
    const token = tokenOf(event)
    if (token === null || event.repeat || event.defaultPrevented) {
      return false
    }
    const typing = isTyping(event.target)
    const modal = typeof document !== 'undefined' && document.querySelector('dialog[open]') !== null
    if (modal && !token.startsWith('mod+')) {
      pending = []
      return false
    }
    if (pending.length > 0 && now() - lastAt > timeoutMs) {
      pending = []
    }
    // first try to continue a sequence, then the key on its own
    for (const candidate of pending.length > 0 ? [[...pending, token], [token]] : [[token]]) {
      const keys = candidate.join(' ')
      const binding = find(keys, typing)
      if (binding) {
        pending = []
        event.preventDefault()
        binding.run(event)
        return true
      }
      if (isPrefix(keys, typing)) {
        pending = candidate
        lastAt = now()
        event.preventDefault()
        return true
      }
    }
    pending = []
    return false
  }

  return {
    handle,
    add(binding: Binding): () => void {
      bindings.add(binding)
      return () => {
        bindings.delete(binding)
      }
    },
  }
}

// BLOCK 3: the app's one keymap and its one listener, installed on first use
export const keyboard = createKeyboard()
let installed = false

function install() {
  if (!installed && typeof document !== 'undefined') {
    document.addEventListener('keydown', (event) => {
      keyboard.handle(event)
    })
    installed = true
  }
}

/** Registers a shortcut while the component is mounted; `run` always sees the latest props. */
export function useShortcut(keys: string, run: () => void, options: { whenTyping?: boolean } = {}) {
  const handler = useEffectEvent(run)
  const whenTyping = options.whenTyping ?? false
  useEffect(() => {
    install()
    return keyboard.add({
      keys,
      whenTyping,
      run: () => {
        handler()
      },
    })
  }, [keys, whenTyping])
}

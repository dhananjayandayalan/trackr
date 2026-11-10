import { useEffect, useEffectEvent } from 'react'

/**
 * A single-key shortcut for the whole page, like `c` for Create (00-product §3.13). It must never fire while
 * the user is typing: a `c` in a comment is a letter, not a command. So it is ignored when focus is in a text
 * field or an editable element, with a modifier held (Ctrl+C is copy), on key repeat, or while a modal dialog
 * is open (the page behind it is inert).
 */
export function useKeyboardShortcut(key: string, onPress: () => void) {
  // useEffectEvent: the listener always calls the latest onPress without re-subscribing on every render
  const handle = useEffectEvent(onPress)

  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if (event.key !== key || event.repeat || event.ctrlKey || event.metaKey || event.altKey) {
        return
      }
      if (isTyping(event.target) || document.querySelector('dialog[open]')) {
        return
      }
      event.preventDefault()
      handle()
    }
    document.addEventListener('keydown', listener)
    return () => {
      document.removeEventListener('keydown', listener)
    }
  }, [key])
}

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false
  }
  if (target.isContentEditable) {
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

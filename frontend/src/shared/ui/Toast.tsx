import { useEffect, useRef, useSyncExternalStore } from 'react'
import { Button } from './Button.tsx'
import { Icon } from './Icon.tsx'
import styles from './Toast.module.css'

// BLOCK 1: the store — a module-level list plus subscribers. It lives outside React on purpose: the query
// cache's error hook (not a component) must be able to show a toast too. Components read it with
// useSyncExternalStore, React's hook for "state that someone else owns".
export interface ToastAction {
  label: string
  onClick: () => void
}

export interface ToastMessage {
  id: number
  text: string
  tone: 'info' | 'success' | 'error'
  /** one button in the toast, e.g. "View" after creating an issue; clicking it also dismisses the toast */
  action?: ToastAction | undefined
}

let toasts: readonly ToastMessage[] = []
let nextId = 1
const listeners = new Set<() => void>()
const LIFETIME_MS = 5000

function emit() {
  for (const listener of listeners) {
    listener()
  }
}

export function showToast(text: string, tone: ToastMessage['tone'] = 'info', action?: ToastAction): number {
  const id = nextId++
  // a new array each time: useSyncExternalStore compares snapshots by identity
  toasts = [...toasts, { id, text, tone, action }]
  emit()
  setTimeout(() => {
    dismissToast(id)
  }, LIFETIME_MS)
  return id
}

export function dismissToast(id: number) {
  toasts = toasts.filter((toast) => toast.id !== id)
  emit()
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** In components: `const toast = useToast(); toast('Saved', 'success')`. */
export function useToast() {
  return showToast
}

// BLOCK 2: the view — one container in the top layer. popover="manual": the browser draws it above
// everything (dialogs included) without z-index wars, and only our code opens or closes it.
// role="status" + aria-live: screen readers announce new toasts without moving focus.
export function Toaster() {
  const current = useSyncExternalStore(subscribe, () => toasts)
  const region = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const element = region.current
    // jsdom (tests) has no Popover API; the list still renders, just not in the top layer
    if (!element || typeof element.showPopover !== 'function') {
      return
    }
    const open = element.matches(':popover-open')
    if (current.length > 0 && !open) {
      element.showPopover()
    } else if (current.length === 0 && open) {
      element.hidePopover()
    }
  }, [current])

  return (
    <div ref={region} popover="manual" className={styles.region} role="status" aria-live="polite">
      {current.map((toast) => (
        <div key={toast.id} className={styles.toast} data-tone={toast.tone}>
          <Icon name={toast.tone === 'error' ? 'close' : 'check'} size="sm" />
          <p className={styles.text}>{toast.text}</p>
          {toast.action && (
            <Button
              size="sm"
              onClick={() => {
                toast.action?.onClick()
                dismissToast(toast.id)
              }}
            >
              {toast.action.label}
            </Button>
          )}
          <Button
            variant="ghost"
            size="sm"
            icon="close"
            aria-label="Dismiss"
            onClick={() => {
              dismissToast(toast.id)
            }}
          />
        </div>
      ))}
    </div>
  )
}

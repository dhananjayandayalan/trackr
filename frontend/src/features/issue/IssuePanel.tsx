import { Suspense, lazy, useEffect, useEffectEvent, useRef } from 'react'
import { ErrorBoundary } from './ErrorBoundary.tsx'
import styles from './IssuePanel.module.css'

// the issue view is its own chunk (~18 KB gzip), fetched the first time a panel opens: a board or backlog that
// nobody opens a card on doesn't pay for it (phase 18, the first-load budget). The Suspense below already shows
// "Loading…" while the issue's data arrives; it covers the chunk too.
const IssueView = lazy(() => import('./IssueView.tsx').then((module) => ({ default: module.IssueView })))

/**
 * The issue as a side panel over the board or backlog (00-product §3.7). The page under it stays mounted —
 * the board keeps its scroll position, its live stream and its drag state — because the panel is not a
 * route of its own: it's the `?issue=MAR-12` search param of the page's route. A link with that param opens
 * it, the back button closes it, and the URL can be shared.
 *
 * It slides in with a view transition: navigating with `viewTransition: true` wraps the change in
 * document.startViewTransition, and the panel's view-transition-name gives the browser one element to
 * animate on its own (IssuePanel.module.css).
 */
export function IssuePanel({ issueKey, onClose }: { issueKey: string; onClose: () => void }) {
  const panel = useRef<HTMLElement>(null)
  const close = useEffectEvent(onClose)

  // BLOCK 1: Esc closes the panel — unless something on top of it (a menu, an editor, a dialog) is open: that
  // one gets the Esc. Capture phase, so we look before the browser closes the popover.
  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return
      if (document.querySelector(':popover-open, dialog[open]')) return
      close()
    }
    document.addEventListener('keydown', listener, { capture: true })
    return () => {
      document.removeEventListener('keydown', listener, { capture: true })
    }
  }, [])

  // BLOCK 2: focus moves to the issue's title when another issue opens, so keyboard and screen reader users
  // land in the panel instead of somewhere on the board behind it
  useEffect(() => {
    requestAnimationFrame(() => {
      panel.current?.querySelector<HTMLElement>('h2')?.focus({ preventScroll: true })
    })
  }, [issueKey])

  return (
    <aside ref={panel} className={styles.panel} aria-label={`Issue ${issueKey}`}>
      <ErrorBoundary key={issueKey} onClose={onClose}>
        <Suspense fallback={<p className={styles.loading}>Loading {issueKey}…</p>}>
          <IssueView issueKey={issueKey} onClose={onClose} />
        </Suspense>
      </ErrorBoundary>
    </aside>
  )
}

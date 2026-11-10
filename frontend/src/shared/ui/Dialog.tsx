import { useEffect, useId, useRef, type ReactNode } from 'react'
import { Button } from './Button.tsx'
import styles from './Dialog.module.css'

interface DialogProps {
  open: boolean
  /** called when the dialog closes by itself (Esc, the close button) — set your `open` state to false */
  onClose: () => void
  title: string
  /** one line under the title */
  description?: ReactNode
  /** lg: room for a two-column form (Create issue) */
  size?: 'md' | 'lg'

  children: ReactNode
}

/**
 * A modal dialog on the native <dialog> element (08-css-volt §5). showModal() gives us, for free: the top
 * layer (no z-index), a ::backdrop, focus moved into the dialog and kept there (the rest of the page is
 * inert), Esc to close, and focus returned to whatever opened it. Controlled: the `open` prop decides.
 */
export function Dialog({ open, onClose, title, description, size = 'md', children }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const descriptionId = useId()

  // BLOCK 1: prop → element. showModal()/close() are imperative, so an effect syncs them with `open`.
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) {
      return
    }
    if (open && !dialog.open) {
      // jsdom (tests) may lack showModal; the open attribute is enough there
      if (typeof dialog.showModal === 'function') dialog.showModal()
      else dialog.setAttribute('open', '')
    } else if (!open && dialog.open) {
      if (typeof dialog.close === 'function') dialog.close()
      else dialog.removeAttribute('open')
    }
  }, [open])

  // BLOCK 2: element → prop. Esc closes the <dialog> natively and fires "close": tell the owner.
  return (
    <dialog
      ref={ref}
      className={styles.root}
      data-size={size}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      onClose={onClose}
    >
      <header className={styles.header}>
        <div>
          <h2 id={titleId} className={styles.title}>
            {title}
          </h2>
          {description && (
            <p id={descriptionId} className={styles.description}>
              {description}
            </p>
          )}
        </div>
        <Button variant="ghost" size="sm" icon="close" aria-label="Close" onClick={onClose} />
      </header>
      {/* rendered only while open: a closed dialog's form starts fresh next time */}
      {open && <div className={styles.body}>{children}</div>}
    </dialog>
  )
}

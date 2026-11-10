import {
  useId,
  useState,
  type CSSProperties,
  type ReactNode,
  type SubmitEvent,
  type ToggleEvent,
} from 'react'
import { Button } from '@/shared/ui/Button.tsx'
import styles from './InlineField.module.css'

interface InlineFieldProps {
  label: string
  /** what the field shows when not editing */
  value: ReactNode
  canEdit: boolean
  /**
   * The editor's controls. Rendered only while open, so they start from the current value every time.
   * `save` gets the form's data; return false to keep the editor open (e.g. the input is not valid).
   */
  editor: () => ReactNode
  onSave: (form: FormData) => boolean | undefined
  /** extra buttons next to Save, e.g. "Clear" */
  extraActions?: (close: () => void) => ReactNode
}

/**
 * One row of the details list: label, value — and, for members, the value is a button that opens a small
 * editor in a popover anchored to it (08-css-volt §5). Popover + anchor positioning do the hard parts: the
 * editor floats above the panel's scroll container (top layer, no clipping, no z-index), Esc and a click
 * outside cancel, and it flips above the value when there's no room below.
 */
export function InlineField({ label, value, canEdit, editor, onSave, extraActions }: InlineFieldProps) {
  const id = useId()
  const editorId = `${id}-editor`
  const [open, setOpen] = useState(false)
  const anchor = { '--_anchor': `--field-${id.replace(/[^a-zA-Z0-9]/g, '-')}` } as CSSProperties

  // looked up by id when called (on a click), not through a ref: this function is handed to extraActions while
  // rendering, and the React Compiler can't prove that a ref read inside it happens later
  const close = () => {
    const popover = document.getElementById(editorId)
    if (popover && typeof popover.hidePopover === 'function') {
      popover.hidePopover()
    } else {
      setOpen(false)
    }
  }

  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (onSave(new FormData(event.currentTarget)) !== false) {
      close()
    }
  }

  return (
    <div className={styles.row} style={anchor}>
      <dt className={styles.label}>{label}</dt>
      <dd className={styles.value}>
        {canEdit ? (
          <button
            type="button"
            className={styles.trigger}
            popoverTarget={editorId}
            aria-label={`${label}: edit`}
          >
            {value}
          </button>
        ) : (
          value
        )}
        {canEdit && (
          <div
            id={editorId}
            popover="auto"
            className={styles.editor}
            onToggle={(event: ToggleEvent<HTMLDivElement>) => {
              setOpen(event.newState === 'open')
              if (event.newState === 'open') {
                // the first control, once React has rendered the editor
                const element = event.currentTarget
                requestAnimationFrame(() => {
                  element.querySelector<HTMLElement>('input, select, textarea')?.focus()
                })
              }
            }}
          >
            {open && (
              <form className={styles.form} onSubmit={submit} aria-label={`Edit ${label}`}>
                {editor()}
                <div className={styles.actions}>
                  {extraActions?.(close)}
                  <Button size="sm" variant="ghost" onClick={close}>
                    Cancel
                  </Button>
                  <Button size="sm" variant="primary" type="submit">
                    Save
                  </Button>
                </div>
              </form>
            )}
          </div>
        )}
      </dd>
    </div>
  )
}

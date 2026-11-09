import { useId, type ReactNode } from 'react'
import styles from './Field.module.css'

/** What Field hands its control: the id its <label> points at, and the ARIA wiring to hint and error. */
export interface ControlProps {
  id: string
  'aria-invalid'?: true
  'aria-describedby'?: string
}

interface FieldProps {
  label: ReactNode
  hint?: ReactNode
  /** A message from validation or the server's errors[]; also marks the control invalid. */
  error?: string | undefined
  children: (control: ControlProps) => ReactNode
}

/**
 * Label + control + hint + error, wired for assistive tech: the label names the control, and the hint and
 * error are read after it (aria-describedby). A render prop hands the ids to whatever control it is.
 */
export function Field({ label, hint, error, children }: FieldProps) {
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ')

  return (
    <div className={styles.root} data-invalid={error ? true : undefined}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      {children({
        id,
        ...(error ? { 'aria-invalid': true as const } : {}),
        ...(describedBy ? { 'aria-describedby': describedBy } : {}),
      })}
      {hint && (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className={styles.error}>
          {error}
        </p>
      )}
    </div>
  )
}

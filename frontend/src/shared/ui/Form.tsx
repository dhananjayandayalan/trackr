import type { ReactNode } from 'react'
import { useFormStatus } from 'react-dom'
import { Button, type ButtonProps } from './Button.tsx'
import styles from './Form.module.css'

/** A form's submit button: disabled, with its pending label, while the form's action runs (useFormStatus). */
export function SubmitButton({
  children,
  pendingLabel,
  variant = 'primary',
}: {
  children: ReactNode
  pendingLabel: string
  variant?: ButtonProps['variant']
}) {
  const { pending } = useFormStatus()
  return (
    <Button type="submit" variant={variant} disabled={pending}>
      {pending ? pendingLabel : children}
    </Button>
  )
}

/** What went wrong that isn't one field's fault. role=alert: announced as soon as it appears. */
export function FormAlert({ message }: { message: string | undefined }) {
  return message ? (
    <p role="alert" className={styles.alert}>
      {message}
    </p>
  ) : null
}

/** A dialog form's buttons: cancel, then the primary action, at the end of the row. */
export function FormActions({ children }: { children: ReactNode }) {
  return <div className={styles.actions}>{children}</div>
}

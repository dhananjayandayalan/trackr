import styles from './Progress.module.css'

interface ProgressProps {
  /** 0–100 */
  value: number
  /** What is progressing, e.g. "MAR done". */
  label: string
}

/**
 * A progress bar. The width is the registered custom property --progress (tokens.css: @property, type
 * <percentage>, inherits: false), so a change animates. It is set on the bar itself: a non-inheriting
 * property set on the outer element would never reach the child that reads it.
 */
export function Progress({ value, label }: ProgressProps) {
  const percent = Math.min(100, Math.max(0, Math.round(value)))
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      className={styles.root}
    >
      <div className={styles.bar} style={{ '--progress': `${percent}%` }} />
    </div>
  )
}

import type { ReactNode } from 'react'
import { Icon } from './Icon.tsx'
import type { IconName } from './icons.ts'
import styles from './EmptyState.module.css'

interface EmptyStateProps {
  icon: IconName
  title: string
  children?: ReactNode
  /** A button or link that gets the user out of the empty state. */
  action?: ReactNode
}

/** What a list shows when it has nothing: what's missing and, where possible, how to change that. */
export function EmptyState({ icon, title, children, action }: EmptyStateProps) {
  return (
    <div className={styles.root}>
      <span className={styles.icon}>
        <Icon name={icon} size="lg" />
      </span>
      <p className={styles.title}>{title}</p>
      {children && <div className={styles.text}>{children}</div>}
      {action}
    </div>
  )
}

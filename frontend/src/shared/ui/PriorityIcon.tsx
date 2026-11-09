import { Icon } from './Icon.tsx'
import styles from './PriorityIcon.module.css'

export type Priority = 'highest' | 'high' | 'medium' | 'low' | 'lowest'

const names: Record<Priority, string> = {
  highest: 'Highest priority',
  high: 'High priority',
  medium: 'Medium priority',
  low: 'Low priority',
  lowest: 'Lowest priority',
}

/** Chevrons pointing up or down, coloured by priority; the shape alone already tells them apart. */
export function PriorityIcon({ priority }: { priority: Priority }) {
  return (
    <Icon
      name={`prio-${priority}`}
      label={names[priority]}
      size="sm"
      className={styles.root}
      data-priority={priority}
      strokeWidth={3}
    />
  )
}

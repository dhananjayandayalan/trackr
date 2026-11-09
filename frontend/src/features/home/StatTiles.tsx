import { useSuspenseQuery } from '@tanstack/react-query'
import { Icon } from '@/shared/ui/Icon.tsx'
import type { IconName } from '@/shared/ui/icons.ts'
import { statsQuery } from './queries.ts'
import styles from './StatTiles.module.css'

interface Tile {
  label: string
  value: number
  icon: IconName
  tone: 'progress' | 'pink' | 'review' | 'accent'
}

/** Four numbers about me, from one request (/me/stats counts them in a single SQL statement). */
export function StatTiles() {
  const { data: stats } = useSuspenseQuery(statsQuery())
  const tiles: Tile[] = [
    { label: 'Assigned to me', value: stats.assignedOpen, icon: 'user', tone: 'progress' },
    { label: 'Due this week', value: stats.dueThisWeek, icon: 'calendar', tone: 'pink' },
    { label: 'Waiting on review', value: stats.waitingReview, icon: 'eye', tone: 'review' },
    { label: 'Unread mentions', value: stats.unreadMentions, icon: 'comment', tone: 'accent' },
  ]

  return (
    // a description list: each label (dt) describes a value (dd) — what a screen reader should pair up
    <dl className={styles.root}>
      {tiles.map((tile) => (
        <div key={tile.label} className={styles.tile} data-tone={tile.tone}>
          <dt className={styles.label}>
            <Icon name={tile.icon} size="sm" />
            {tile.label}
          </dt>
          <dd className={styles.value}>{tile.value}</dd>
        </div>
      ))}
    </dl>
  )
}

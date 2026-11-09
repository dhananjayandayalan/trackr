import { useSuspenseQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { z } from 'zod'
import type { ActivityItem } from '@/shared/api/types.ts'
import { timeAgo } from '@/shared/lib/dates.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { activityQuery } from './queries.ts'
import homeStyles from './HomePage.module.css'
import styles from './ActivityFeed.module.css'

/** The latest 20 changes across every project I can see (00-product §3.2). */
export function ActivityFeed() {
  const { data: items } = useSuspenseQuery(activityQuery())
  const [now] = useState(Date.now)

  return (
    <section className={homeStyles.section} aria-labelledby="team-activity">
      <h2 id="team-activity" className={homeStyles.sectionTitle}>
        Team activity
      </h2>
      {items.length === 0 ? (
        <p className={styles.empty}>No activity yet.</p>
      ) : (
        <ol role="list" className={styles.list}>
          {items.map((item) => (
            <li key={item.id} className={styles.item}>
              <Avatar
                name={item.actor.displayName}
                initials={item.actor.initials}
                color={avatarColor(item.actor.avatarColor)}
                size="sm"
              />
              <p className={styles.text}>
                <strong>{item.actor.displayName}</strong> {describe(item)}
                <time className={styles.time} dateTime={item.occurredAt}>
                  {timeAgo(item.occurredAt, now)}
                </time>
              </p>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

// a status value in the history is {id, name}; parsed, not cast: old or odd rows just say less
const statusValue = z.object({ name: z.string() })

/** One history entry as a sentence. The kinds are issue_event.kind (V008). */
export function describe(item: ActivityItem): string {
  const issue = item.issueKey
  switch (item.kind) {
    case 'created':
      return `created ${issue}`
    case 'transitioned': {
      const to = statusValue.safeParse(item.newValue)
      return to.success ? `moved ${issue} to ${to.data.name}` : `changed the status of ${issue}`
    }
    case 'moved':
      return `re-ranked ${issue}`
    case 'field_changed':
      return `changed the ${item.field ?? 'details'} of ${issue}`
    case 'commented':
      return `commented on ${issue}`
    case 'linked':
      return `linked ${issue}`
    case 'logged_work':
      return `logged work on ${issue}`
    case 'deleted':
      return `deleted ${issue}`
    default:
      return `updated ${issue}`
  }
}

import { useSuspenseQuery } from '@tanstack/react-query'
import { createContext, use } from 'react'
import { meQuery } from '@/features/auth/queries.ts'
import { AvatarGroup, type Person } from '@/shared/ui/AvatarGroup.tsx'
import type { ProjectLive } from './live.ts'
import styles from './LiveIndicator.module.css'

/** The project's live stream, opened once by ProjectShell, read by any header that shows it. */
export const ProjectLiveContext = createContext<ProjectLive>({ status: 'connecting', viewers: [] })

const labels = {
  connecting: 'Connecting…',
  open: 'Live',
  reconnecting: 'Reconnecting…',
  closed: 'Offline',
} as const

/**
 * The connection dot (07-frontend §3) and who else has this project open. The people come from the project's
 * member list; anyone not in it (a workspace admin) is left out.
 */
export function LiveIndicator({ people }: { people: readonly Person[] }) {
  const { status, viewers } = use(ProjectLiveContext)
  const { data: me } = useSuspenseQuery(meQuery())
  const others = viewers
    .filter((id) => id !== me.id)
    .map((id) => people.find((person) => person.id === id))
    .filter((person) => person !== undefined)

  return (
    <div className={styles.root}>
      {others.length > 0 && (
        <AvatarGroup
          label={`Also here: ${others.map((person) => person.displayName).join(', ')}`}
          people={others}
          max={4}
        />
      )}
      {/* role=status: a screen reader hears "Reconnecting…" when the connection drops */}
      <span className={styles.status} data-status={status} role="status">
        <span className={styles.dot} aria-hidden="true" />
        {labels[status]}
      </span>
    </div>
  )
}

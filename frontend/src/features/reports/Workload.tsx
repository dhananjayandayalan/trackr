import type { AssigneeSlice } from '@/shared/api/types.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { share } from './scale.ts'
import styles from './Charts.module.css'

// workload — open points per person, relative to the busiest one
export function Workload({ slices }: { slices: AssigneeSlice[] }) {
  const most = Math.max(1, ...slices.map((slice) => slice.points))
  if (slices.length === 0) {
    return <p className={styles.muted}>Nothing open — no one has work left here.</p>
  }
  return (
    <ul role="list" className={styles.bars} aria-label="Open work per person">
      {slices.map((slice) => (
        <li key={slice.userId ?? 'unassigned'} className={styles.barRow}>
          <span className={styles.barLabel}>
            {slice.initials ? (
              <Avatar
                name={slice.name}
                initials={slice.initials}
                color={avatarColor(slice.avatarColor ?? '')}
                size="sm"
              />
            ) : null}
            {slice.name}
          </span>
          <span className={styles.track} aria-hidden="true">
            <span className={styles.fill} data-tone="chart" style={share(slice.points, most)} />
          </span>
          <span className={styles.barValue}>
            {slice.issues} issue{slice.issues === 1 ? '' : 's'} · {slice.points} pts
          </span>
        </li>
      ))}
    </ul>
  )
}

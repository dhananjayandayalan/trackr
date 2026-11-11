import type { StatusSlice } from '@/shared/api/types.ts'
import { statusTone } from '@/shared/lib/enums.ts'
import { share } from './scale.ts'
import styles from './Charts.module.css'

// status breakdown — issues per status, the bar tinted with the status's own colour token
export function StatusBreakdown({ slices }: { slices: StatusSlice[] }) {
  const total = slices.reduce((sum, slice) => sum + slice.issues, 0)
  return (
    <ul role="list" className={styles.bars} aria-label={`${String(total)} issues by status`}>
      {slices.map((slice) => (
        <li key={slice.statusId} className={styles.barRow}>
          <span className={styles.barLabel}>{slice.name}</span>
          <span className={styles.track} aria-hidden="true">
            <span
              className={styles.fill}
              data-tone={statusTone(slice.color)}
              style={share(slice.issues, total)}
            />
          </span>
          <span className={styles.barValue}>
            {slice.issues} · {slice.points} pts
          </span>
        </li>
      ))}
    </ul>
  )
}

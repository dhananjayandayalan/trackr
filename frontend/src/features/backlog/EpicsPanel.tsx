import { useId } from 'react'
import type { EpicSummary } from '@/shared/api/types.ts'
import { Progress } from '@/shared/ui/Progress.tsx'
import styles from './EpicsPanel.module.css'

/**
 * The epics beside the backlog (00-product §3.6): each with its progress (done children / all children), and
 * a click filters the lists to that epic. The selection is the `epic` search param, passed in.
 */
export function EpicsPanel({
  epics,
  selected,
  onSelect,
}: {
  epics: readonly EpicSummary[]
  selected: string | undefined
  onSelect: (epicId: string | undefined) => void
}) {
  const titleId = useId()
  return (
    <aside className={styles.root} aria-labelledby={titleId}>
      <h2 id={titleId} className={styles.title}>
        Epics
      </h2>
      {epics.length === 0 ? (
        <p className={styles.empty}>No epics yet. Epics group stories that ship together.</p>
      ) : (
        <ul role="list" className={styles.list}>
          <li>
            <button
              type="button"
              className={styles.epic}
              aria-pressed={selected === undefined}
              onClick={() => {
                onSelect(undefined)
              }}
            >
              <span className={styles.name}>All issues</span>
            </button>
          </li>
          {epics.map((epic) => (
            <li key={epic.id}>
              <button
                type="button"
                className={styles.epic}
                data-color={epic.color}
                aria-pressed={selected === epic.id}
                onClick={() => {
                  onSelect(selected === epic.id ? undefined : epic.id)
                }}
              >
                <span className={styles.name}>{epic.summary}</span>
                <span className={styles.meta}>
                  {epic.key} · {epic.doneCount} of {epic.issueCount} done
                </span>
                <Progress value={epic.progressPct} label={`${epic.summary}: ${epic.progressPct}% done`} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </aside>
  )
}

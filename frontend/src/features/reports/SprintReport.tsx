import type { ReportIssue, SprintReportView } from '@/shared/api/types.ts'
import { issueType } from '@/shared/lib/enums.ts'
import { TypeIcon } from '@/shared/ui/TypeIcon.tsx'
import styles from './Charts.module.css'

// sprint report — three lists with their point totals
export function SprintReport({ report }: { report: SprintReportView }) {
  const sections = [
    { title: 'Completed', issues: report.completed, points: report.completedPoints },
    { title: 'Not completed', issues: report.notCompleted, points: null },
    { title: 'Added mid-sprint', issues: report.added, points: report.addedPoints },
  ]
  return (
    <div className={styles.report}>
      <dl className={styles.totals}>
        <div>
          <dt>Committed</dt>
          <dd>{report.committedPoints}</dd>
        </div>
        <div>
          <dt>Completed</dt>
          <dd>{report.completedPoints}</dd>
        </div>
        <div>
          <dt>Added</dt>
          <dd>{report.addedPoints}</dd>
        </div>
        <div>
          <dt>Removed</dt>
          <dd>{report.removedPoints}</dd>
        </div>
      </dl>
      {sections.map((section) => (
        <details key={section.title} className={styles.section} open={section.title !== 'Completed'}>
          <summary>
            {section.title} <span className={styles.muted}>({section.issues.length})</span>
          </summary>
          {section.issues.length === 0 ? (
            <p className={styles.muted}>None.</p>
          ) : (
            <ul role="list" className={styles.issues}>
              {section.issues.map((issue) => (
                <IssueLine key={issue.key} issue={issue} />
              ))}
            </ul>
          )}
        </details>
      ))}
    </div>
  )
}

function IssueLine({ issue }: { issue: ReportIssue }) {
  return (
    <li className={styles.issue}>
      <TypeIcon type={issueType(issue.type)} />
      <span className={styles.issueKey}>{issue.key}</span>
      <span className={styles.issueSummary}>{issue.summary}</span>
      <span className={styles.muted}>{issue.statusName}</span>
      <span className={styles.barValue}>{issue.storyPoints ?? '—'}</span>
    </li>
  )
}

import { useQuery } from '@tanstack/react-query'
import { getRouteApi } from '@tanstack/react-router'
import { useId, type ReactNode } from 'react'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { Burndown } from './Burndown.tsx'
import { breakdownQuery, burndownQuery, sprintReportQuery, velocityQuery } from './queries.ts'
import { SprintReport } from './SprintReport.tsx'
import { StatusBreakdown } from './StatusBreakdown.tsx'
import { Velocity } from './Velocity.tsx'
import { Workload } from './Workload.tsx'
import styles from './ReportsPage.module.css'

const route = getRouteApi('/_authed/p/$projectKey/reports')

/**
 * Reports (00-product §3.10). Each card loads on its own (useQuery, not suspense): a Kanban project has no
 * sprints, so burndown and sprint report answer 404 — that card says so, and the breakdowns still show.
 * The sprint is the ?sprint= search param; without it the api picks the active sprint, else the last closed one.
 */
export function ReportsPage() {
  const { projectKey } = route.useParams()
  const { sprint } = route.useSearch()
  const navigate = route.useNavigate()
  const sprintSelect = useId()

  const burndown = useQuery(burndownQuery(projectKey, sprint))
  const velocity = useQuery(velocityQuery(projectKey))
  const report = useQuery(sprintReportQuery(projectKey, sprint))
  const breakdown = useQuery(breakdownQuery(projectKey, sprint))
  const closed = velocity.data ?? []

  return (
    <div className={styles.root}>
      <header className={styles.header}>
        <div>
          <p className={styles.eyebrow}>{projectKey}</p>
          <h1>Reports</h1>
        </div>
        {closed.length > 0 && (
          <div className={styles.picker}>
            <label htmlFor={sprintSelect}>Sprint</label>
            <Select
              id={sprintSelect}
              value={sprint ?? ''}
              onChange={(event) => {
                void navigate({
                  search: { sprint: event.target.value === '' ? undefined : event.target.value },
                })
              }}
            >
              <option value="">Current sprint</option>
              {closed.toReversed().map((entry) => (
                <option key={entry.sprintId} value={entry.sprintId}>
                  {entry.name}
                </option>
              ))}
            </Select>
          </div>
        )}
      </header>

      <div className={styles.grid}>
        <Card title={burndown.data ? `Burndown · ${burndown.data.sprintName}` : 'Burndown'} wide>
          <Loaded
            query={burndown}
            none="No active or closed sprint yet: the burndown starts with the first sprint."
          >
            {(data) => <Burndown data={data} />}
          </Loaded>
        </Card>

        <Card title="Velocity">
          <Loaded query={velocity} none="">
            {(data) =>
              data.length === 0 ? (
                <p className={styles.muted}>Velocity shows once a sprint has been completed.</p>
              ) : (
                <Velocity sprints={data} />
              )
            }
          </Loaded>
        </Card>

        <Card title={report.data ? `Sprint report · ${report.data.name}` : 'Sprint report'}>
          <Loaded query={report} none="No sprint to report on yet.">
            {(data) => <SprintReport report={data} />}
          </Loaded>
        </Card>

        <Card
          title={breakdown.data?.sprintId ? 'Status breakdown · sprint' : 'Status breakdown · all issues'}
        >
          <Loaded query={breakdown} none="">
            {(data) => <StatusBreakdown slices={data.byStatus} />}
          </Loaded>
        </Card>

        <Card title="Workload">
          <Loaded query={breakdown} none="">
            {(data) => <Workload slices={data.byAssignee} />}
          </Loaded>
        </Card>
      </div>
    </div>
  )
}

function Card({ title, wide, children }: { title: string; wide?: boolean; children: ReactNode }) {
  const id = useId()
  return (
    <section className={styles.card} data-wide={wide ? true : undefined} aria-labelledby={id}>
      <h2 id={id} className={styles.cardTitle}>
        {title}
      </h2>
      {children}
    </section>
  )
}

/** Loading, "nothing to show" (404), error, or the data. */
function Loaded<T>({
  query,
  none,
  children,
}: {
  query: { data: T | undefined; error: Error | null; isPending: boolean }
  none: string
  children: (data: T) => ReactNode
}) {
  if (query.data !== undefined) {
    return <>{children(query.data)}</>
  }
  if (query.error) {
    return isApiError(query.error) && query.error.status === 404 ? (
      <EmptyState icon="reports" title="Nothing to show">
        {none}
      </EmptyState>
    ) : (
      <p className={styles.muted}>Couldn't load this: {messageOf(query.error)}</p>
    )
  }
  return <p className={styles.muted}>Loading…</p>
}

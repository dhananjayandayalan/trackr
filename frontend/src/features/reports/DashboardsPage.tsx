import { useSuspenseQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { projectsQuery } from '@/features/projects/queries.ts'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'
import { Progress } from '@/shared/ui/Progress.tsx'
import { ProjectTile } from '@/shared/ui/ProjectTile.tsx'
import styles from './DashboardsPage.module.css'

/**
 * Dashboards (00-product §3.13: "Dashboards = reports"): every project I can see, with its progress and the
 * way into its reports and timeline. The numbers are the project list's, so this page costs no extra request.
 */
export function DashboardsPage() {
  const { data: projects } = useSuspenseQuery(projectsQuery())
  return (
    <div className={styles.root}>
      <header>
        <p className={styles.eyebrow}>Dashboards</p>
        <h1>Reports by project</h1>
      </header>
      {projects.length === 0 ? (
        <EmptyState icon="reports" title="No projects yet">
          Reports appear once you have a project.
        </EmptyState>
      ) : (
        <ul role="list" className={styles.grid}>
          {projects.map((project) => (
            <li key={project.id} className={styles.card}>
              <div className={styles.head}>
                <ProjectTile projectKey={project.key} color={project.color} />
                <div>
                  <h2 className={styles.name}>{project.name}</h2>
                  <p className={styles.muted}>
                    {project.activeSprint ?? 'No active sprint'} · {project.openIssues} open
                  </p>
                </div>
              </div>
              <Progress value={project.progressPct} label={`${project.key} progress`} />
              <div className={styles.links}>
                <Link
                  to="/p/$projectKey/reports"
                  params={{ projectKey: project.key }}
                  className={styles.link}
                >
                  Reports
                </Link>
                <Link
                  to="/p/$projectKey/timeline"
                  params={{ projectKey: project.key }}
                  className={styles.link}
                >
                  Timeline
                </Link>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

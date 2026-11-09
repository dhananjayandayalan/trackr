import { useSuspenseQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { projectsQuery } from '@/features/projects/queries.ts'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'
import { ProjectTile } from '@/shared/ui/ProjectTile.tsx'
import homeStyles from './HomePage.module.css'
import styles from './RecentProjects.module.css'

/**
 * The last five projects I opened. Same query as the Projects page (one cache entry, shared): the list
 * carries lastVisitedAt, so "recent" is a sort in the browser, not another endpoint.
 */
export function RecentProjects() {
  const { data: projects } = useSuspenseQuery(projectsQuery())
  const recent = projects
    .filter((project) => project.lastVisitedAt)
    .toSorted((a, b) => (b.lastVisitedAt ?? '').localeCompare(a.lastVisitedAt ?? ''))
    .slice(0, 5)

  return (
    <section className={homeStyles.section} aria-labelledby="recent-projects">
      <h2 id="recent-projects" className={homeStyles.sectionTitle}>
        Recent projects <Link to="/projects">All projects</Link>
      </h2>
      {recent.length === 0 ? (
        <EmptyState icon="folder" title="No recent projects">
          Projects you open show up here.
        </EmptyState>
      ) : (
        <ul role="list" className={styles.list}>
          {recent.map((project) => (
            <li key={project.id}>
              <Link to="/p/$projectKey" params={{ projectKey: project.key }} className={styles.item}>
                <ProjectTile projectKey={project.key} color={project.color} />
                <span className={styles.text}>
                  <span className={styles.name}>{project.name}</span>
                  <span className={styles.meta}>
                    {project.openIssues} open{project.activeSprint ? ` · ${project.activeSprint}` : ''}
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

import { useSuspenseQuery } from '@tanstack/react-query'
import { getRouteApi, Link } from '@tanstack/react-router'
import { useDeferredValue, useId, useState } from 'react'
import type { ProjectSummary, Template } from '@/shared/api/types.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { buttonClass } from '@/shared/ui/Button.tsx'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Progress } from '@/shared/ui/Progress.tsx'
import { ProjectTile } from '@/shared/ui/ProjectTile.tsx'
import { Segmented } from '@/shared/ui/Segmented.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { ProjectCard, StarButton, templateNames } from './ProjectCard.tsx'
import { projectsQuery } from './queries.ts'
import styles from './ProjectsPage.module.css'

const route = getRouteApi('/_authed/_app/projects')

/**
 * The projects directory (00-product §3.3). View and search text live in the URL (?view=list&q=mar), so
 * reload, back and a shared link show the same thing; filtering happens in the browser on one cached list.
 */
export function ProjectsPage() {
  const { view = 'grid', q = '' } = route.useSearch()
  const navigate = route.useNavigate()
  const { data: projects } = useSuspenseQuery(projectsQuery())
  const [template, setTemplate] = useState<Template | 'all'>('all')
  const searchId = useId()
  const templateId = useId()

  // BLOCK 1: filtering. useDeferredValue: React first re-renders the input with the new letter (urgent),
  // then the list with the deferred text when it has time — fast typing never waits on a long list.
  const query = useDeferredValue(q.trim().toLowerCase())
  const shown = projects.filter(
    (project) =>
      (template === 'all' || project.template === template) &&
      (query === '' ||
        project.name.toLowerCase().includes(query) ||
        project.key.toLowerCase().includes(query)),
  )

  return (
    <div className={styles.root}>
      <header className={styles.header}>
        <h1>Projects</h1>
        <Link to="/projects/new" className={buttonClass} data-variant="primary" data-size="md">
          <Icon name="plus" />
          New project
        </Link>
      </header>

      {/* BLOCK 2: toolbar — every control writes the URL (replace: no history entry per keystroke) */}
      <div className={styles.toolbar}>
        <label htmlFor={searchId} className="visually-hidden">
          Search projects
        </label>
        <TextInput
          id={searchId}
          type="search"
          placeholder="Search by name or key"
          className={styles.search}
          value={q}
          onChange={(event) =>
            void navigate({
              search: (previous) => ({ ...previous, q: event.target.value || undefined }),
              replace: true,
            })
          }
        />
        <label htmlFor={templateId} className="visually-hidden">
          Template
        </label>
        <span className={styles.filter}>
          <Select
            id={templateId}
            value={template}
            onChange={(event) => {
              setTemplate(event.target.value as Template | 'all')
            }}
          >
            <option value="all">All templates</option>
            {Object.entries(templateNames).map(([value, name]) => (
              <option key={value} value={value}>
                {name}
              </option>
            ))}
          </Select>
        </span>
        <Segmented
          label="View"
          value={view}
          options={[
            { value: 'grid', label: 'Grid', icon: 'dashboard', iconOnly: true },
            { value: 'list', label: 'List', icon: 'issues', iconOnly: true },
          ]}
          onChange={(next) =>
            void navigate({ search: (previous) => ({ ...previous, view: next }), replace: true })
          }
        />
      </div>

      {/* BLOCK 3: the projects */}
      {shown.length === 0 && projects.length > 0 ? (
        <EmptyState icon="search" title="No matching projects">
          Nothing matches “{q}”. Try a key like MAR, or clear the filters.
        </EmptyState>
      ) : view === 'grid' ? (
        <ul role="list" className={styles.grid}>
          {shown.map((project) => (
            <li key={project.id}>
              <ProjectCard project={project} />
            </li>
          ))}
          <li>
            <Link to="/projects/new" className={styles.newCard}>
              <Icon name="plus" size="lg" />
              New project
            </Link>
          </li>
        </ul>
      ) : (
        <ProjectTable projects={shown} />
      )}
    </div>
  )
}

function ProjectTable({ projects }: { projects: ProjectSummary[] }) {
  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th scope="col">
            <span className="visually-hidden">Starred</span>
          </th>
          <th scope="col">Project</th>
          <th scope="col">Template</th>
          <th scope="col">Lead</th>
          <th scope="col">Open</th>
          <th scope="col">Done</th>
        </tr>
      </thead>
      <tbody>
        {projects.map((project) => (
          <tr key={project.id}>
            <td>
              <StarButton project={project} />
            </td>
            <td>
              <Link to="/p/$projectKey" params={{ projectKey: project.key }} className={styles.tableProject}>
                <ProjectTile projectKey={project.key} color={project.color} size="sm" />
                {project.name}
              </Link>
            </td>
            <td>{templateNames[project.template]}</td>
            <td>
              <span className={styles.lead}>
                <Avatar
                  name={project.lead.displayName}
                  initials={project.lead.initials}
                  color={avatarColor(project.lead.avatarColor)}
                  size="sm"
                />
                {project.lead.displayName}
              </span>
            </td>
            <td className={styles.number}>{project.openIssues}</td>
            <td className={styles.progressCell}>
              <Progress value={project.progressPct} label={`${project.name} done`} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

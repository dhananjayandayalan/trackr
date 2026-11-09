import { Link } from '@tanstack/react-router'
import { useId, useState, type ReactNode } from 'react'
import type { ProjectDetails, Template } from '@/shared/api/types.ts'
import { Icon } from '@/shared/ui/Icon.tsx'
import type { IconName } from '@/shared/ui/icons.ts'
import styles from './Sidebar.module.css'

const planning = [
  { to: '/p/$projectKey/board', label: 'Board', icon: 'board' },
  { to: '/p/$projectKey/backlog', label: 'Backlog', icon: 'backlog' },
  { to: '/p/$projectKey/timeline', label: 'Timeline', icon: 'timeline' },
  { to: '/p/$projectKey/reports', label: 'Reports', icon: 'reports' },
] as const satisfies readonly { to: string; label: string; icon: IconName }[]

const templateNames: Record<Template, string> = {
  scrum: 'Scrum project',
  kanban: 'Kanban project',
  bugs: 'Bug tracking',
  blank: 'Project',
}

/**
 * The project's navigation. Wide: a dark column beside the page. Narrow (the shell's container query):
 * a bar above the page that opens like a drawer — the toggle only exists in that layout (CSS hides it).
 */
export function Sidebar({ project }: { project: ProjectDetails }) {
  // BLOCK 1: drawer state — plain component state; nothing else needs to know
  const [open, setOpen] = useState(false)
  const navId = useId()
  const params = { projectKey: project.key }

  return (
    <aside className={styles.root} data-open={open || undefined}>
      {/* BLOCK 2: which project this is; the tile takes the project's colour */}
      <div className={styles.project}>
        <span className={styles.tile} data-color={project.color} aria-hidden="true">
          {project.key}
        </span>
        <span className={styles.projectText}>
          <strong className={styles.projectName}>{project.name}</strong>
          <span className={styles.projectKind}>{templateNames[project.template]}</span>
        </span>
        {/* BLOCK 3: the drawer toggle — aria-expanded tells a screen reader whether the list is open */}
        <button
          type="button"
          className={styles.toggle}
          aria-expanded={open}
          aria-controls={navId}
          onClick={() => {
            setOpen(!open)
          }}
        >
          Menu
          <Icon name={open ? 'chevron-up' : 'chevron-down'} size="sm" />
        </button>
      </div>

      {/* BLOCK 4: the links; a link to the current route gets aria-current="page" from <Link> */}
      <nav id={navId} aria-label="Project" className={styles.nav}>
        <Group title="Planning">
          {planning.map((item) => (
            <li key={item.label}>
              <Link className={styles.link} to={item.to} params={params}>
                <Icon name={item.icon} size="sm" />
                {item.label}
              </Link>
            </li>
          ))}
          <li>
            <Link className={styles.link} to="/issues">
              <Icon name="issues" size="sm" />
              Issues
            </Link>
          </li>
        </Group>
        <Group title="Project">
          <li>
            <Link
              className={styles.link}
              to="/p/$projectKey/settings/$section"
              params={{ ...params, section: 'details' }}
            >
              <Icon name="settings" size="sm" />
              Settings
            </Link>
          </li>
        </Group>
      </nav>
    </aside>
  )
}

function Group({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <div className={styles.group}>
      <h2 id={id} className={styles.groupTitle}>
        {title}
      </h2>
      <ul role="list" aria-labelledby={id} className={styles.list}>
        {children}
      </ul>
    </div>
  )
}

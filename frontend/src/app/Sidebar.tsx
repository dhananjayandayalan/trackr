import { useId, useState } from 'react'
import { Icon } from '@/shared/ui/Icon.tsx'
import type { IconName } from '@/shared/ui/icons.ts'
import styles from './Sidebar.module.css'

interface Item {
  href: string
  label: string
  icon: IconName
  current?: boolean
}

// static until phase 13: the dev seed's Marketing site project
const planning: Item[] = [
  { href: '/p/MAR/board', label: 'Board', icon: 'board', current: true },
  { href: '/p/MAR/backlog', label: 'Backlog', icon: 'backlog' },
  { href: '/p/MAR/timeline', label: 'Timeline', icon: 'timeline' },
  { href: '/p/MAR/reports', label: 'Reports', icon: 'reports' },
  { href: '/issues?tql=project%20%3D%20MAR', label: 'Issues', icon: 'issues' },
]
const project: Item[] = [{ href: '/p/MAR/settings/details', label: 'Settings', icon: 'settings' }]

/**
 * The project's navigation. Wide: a dark column beside the page. Narrow (the shell's container query):
 * a bar above the page that opens like a drawer — the toggle only exists in that layout (CSS hides it).
 */
export function Sidebar() {
  // BLOCK 1: drawer state — plain component state; nothing else needs to know
  const [open, setOpen] = useState(false)
  const navId = useId()
  const current = planning.find((item) => item.current)

  return (
    <aside className={styles.root} data-open={open || undefined}>
      {/* BLOCK 2: which project this is */}
      <div className={styles.project}>
        <span className={styles.tile} aria-hidden="true">
          MAR
        </span>
        <span className={styles.projectText}>
          <strong className={styles.projectName}>Marketing site</strong>
          <span className={styles.projectKind}>Scrum project</span>
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
          {current?.label}
          <Icon name={open ? 'chevron-up' : 'chevron-down'} size="sm" />
        </button>
      </div>

      {/* BLOCK 4: the links, in two labelled groups */}
      <nav id={navId} aria-label="Project" className={styles.nav}>
        <Group title="Planning" items={planning} />
        <Group title="Project" items={project} />
      </nav>
    </aside>
  )
}

function Group({ title, items }: { title: string; items: Item[] }) {
  const id = useId()
  return (
    <div className={styles.group}>
      <h2 id={id} className={styles.groupTitle}>
        {title}
      </h2>
      <ul role="list" aria-labelledby={id} className={styles.list}>
        {items.map((item) => (
          <li key={item.href}>
            <a className={styles.link} href={item.href} aria-current={item.current ? 'page' : undefined}>
              <Icon name={item.icon} size="sm" />
              {item.label}
            </a>
          </li>
        ))}
      </ul>
    </div>
  )
}

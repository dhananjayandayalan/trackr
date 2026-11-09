import { Avatar } from '@/shared/ui/Avatar.tsx'
import { Button } from '@/shared/ui/Button.tsx'
import { Lozenge } from '@/shared/ui/Lozenge.tsx'
import { PriorityIcon } from '@/shared/ui/PriorityIcon.tsx'
import { TypeIcon } from '@/shared/ui/TypeIcon.tsx'
import styles from './ShellPreview.module.css'

/**
 * Static page content so the shell has something to frame until the real screens arrive (phases 13–14).
 * Deleted when the router renders pages into AppShell.
 */
export function ShellPreview() {
  return (
    <div className={styles.root}>
      <header className={styles.header}>
        <p className={styles.crumbs}>Projects / Marketing site</p>
        <div className={styles.titleRow}>
          <h1>MAR Sprint 2</h1>
          <Lozenge tone="progress">Active</Lozenge>
          <div className={styles.headerActions}>
            <Button icon="filter">Filters</Button>
            <Button variant="accent">Complete sprint</Button>
          </div>
        </div>
        <p className={styles.goal}>Ship the new pricing page · 2 Nov – 16 Nov</p>
      </header>

      <div className={styles.columns}>
        {columns.map((column) => (
          <section key={column.name} className={styles.column} aria-labelledby={`col-${column.tone}`}>
            <h2 id={`col-${column.tone}`} className={styles.columnTitle}>
              {column.name} <span className={styles.count}>{column.cards.length}</span>
            </h2>
            {column.cards.map((card) => (
              <article key={card.key} className={styles.card}>
                <p className={styles.summary}>{card.summary}</p>
                <div className={styles.meta}>
                  <TypeIcon type={card.type} />
                  <span className="mono">{card.key}</span>
                  <PriorityIcon priority={card.priority} />
                  <span className={styles.points}>{card.points}</span>
                  <Avatar
                    name={card.who.name}
                    initials={card.who.initials}
                    color={card.who.color}
                    size="sm"
                  />
                </div>
              </article>
            ))}
          </section>
        ))}
      </div>
    </div>
  )
}

const ada = { name: 'Ada Lovelace', initials: 'AL', color: 'violet' } as const
const grace = { name: 'Grace Hopper', initials: 'GH', color: 'cyan' } as const

const columns = [
  {
    name: 'To do',
    tone: 'todo',
    cards: [
      { key: 'MAR-28', summary: 'Add pricing table', type: 'story', priority: 'high', points: 5, who: ada },
      {
        key: 'MAR-32',
        summary: 'Fix checkout flow',
        type: 'bug',
        priority: 'highest',
        points: 3,
        who: grace,
      },
    ],
  },
  {
    name: 'In progress',
    tone: 'progress',
    cards: [
      { key: 'MAR-25', summary: 'Polish hero banner', type: 'task', priority: 'medium', points: 2, who: ada },
    ],
  },
  {
    name: 'In review',
    tone: 'review',
    cards: [
      { key: 'MAR-26', summary: 'Document sitemap', type: 'story', priority: 'low', points: 1, who: grace },
    ],
  },
  {
    name: 'Done',
    tone: 'done',
    cards: [
      { key: 'MAR-24', summary: 'Test search box', type: 'task', priority: 'lowest', points: 8, who: ada },
    ],
  },
] as const

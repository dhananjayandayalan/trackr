import { useSuspenseInfiniteQuery } from '@tanstack/react-query'
import { getRouteApi, Link } from '@tanstack/react-router'
import { useState } from 'react'
import type { IssueRow } from '@/shared/api/types.ts'
import { timeAgo } from '@/shared/lib/dates.ts'
import { issueType, priority, statusTone } from '@/shared/lib/enums.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'
import { Lozenge } from '@/shared/ui/Lozenge.tsx'
import { PriorityIcon } from '@/shared/ui/PriorityIcon.tsx'
import { Tabs } from '@/shared/ui/Tabs.tsx'
import { TypeIcon } from '@/shared/ui/TypeIcon.tsx'
import { workQuery, type WorkTab } from './queries.ts'
import styles from './WorkTabs.module.css'

const route = getRouteApi('/_authed/_app/')

const tabs = [
  { value: 'assigned', label: 'Assigned to me' },
  { value: 'worked', label: 'Worked on' },
  { value: 'viewed', label: 'Viewed' },
  { value: 'starred', label: 'Starred' },
] as const satisfies readonly { value: WorkTab; label: string }[]

const empty: Record<WorkTab, string> = {
  assigned: 'Nothing open is assigned to you.',
  worked: "You haven't changed an issue in the last 30 days.",
  viewed: 'Issues you open show up here.',
  starred: 'Star an issue to keep it here.',
}

/**
 * The four lists of "Your work". The selected tab is the ?tab= search param: switching tabs is a navigation,
 * so the route's loader fetches the new list (preloading it while the pointer is still on the way), the
 * back button returns to the previous tab, and a shared link opens on the same tab.
 */
export function WorkTabs() {
  const { tab = 'assigned' } = route.useSearch()
  const navigate = route.useNavigate()
  const work = useSuspenseInfiniteQuery(workQuery(tab))
  const rows = work.data.pages.flatMap((page) => page.items)
  // one "now" per mount: rendering stays pure (the same input gives the same output)
  const [now] = useState(Date.now)

  return (
    <section aria-label="Your issues">
      <Tabs
        label="Your work"
        value={tab}
        tabs={tabs}
        onChange={(next) => void navigate({ search: { tab: next } })}
      >
        {rows.length === 0 ? (
          <EmptyState icon="issues" title="All clear">
            {empty[tab]}
          </EmptyState>
        ) : (
          <ul role="list" className={styles.list}>
            {rows.map((row) => (
              <IssueItem key={row.id} row={row} now={now} />
            ))}
          </ul>
        )}
        {work.hasNextPage && (
          <Button
            className={styles.more}
            disabled={work.isFetchingNextPage}
            onClick={() => void work.fetchNextPage()}
          >
            {work.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </Button>
        )}
      </Tabs>
    </section>
  )
}

function IssueItem({ row, now }: { row: IssueRow; now: number }) {
  return (
    <li>
      <Link
        to="/p/$projectKey/issues/$issueKey"
        params={{ projectKey: row.projectKey, issueKey: row.key }}
        className={styles.row}
      >
        <TypeIcon type={issueType(row.type)} />
        <span className={styles.key}>{row.key}</span>
        <span className={styles.summary}>{row.summary}</span>
        <Lozenge tone={statusTone(row.statusColor)}>{row.statusName}</Lozenge>
        <PriorityIcon priority={priority(row.priority)} />
        <time className={styles.time} dateTime={row.at}>
          {timeAgo(row.at, now)}
        </time>
      </Link>
    </li>
  )
}

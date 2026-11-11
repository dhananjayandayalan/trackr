import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useId, useState, type CSSProperties } from 'react'
import type { NotificationView } from '@/shared/api/types.ts'
import { timeAgo } from '@/shared/lib/dates.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { notificationsQuery, unreadCountQuery, useMarkRead, useMeStream } from './queries.ts'
import styles from './NotificationsPopover.module.css'

const verbs: Record<string, string> = {
  assigned: 'assigned you',
  mentioned: 'mentioned you on',
  commented: 'commented on',
  status_changed: 'changed the status of',
}

/**
 * The bell (00-product §3.13): the unread count on the button, the list in a popover. The list loads the first
 * time it opens; after that /me/stream keeps both the list and the badge current while the app is open.
 */
export function NotificationsPopover() {
  useMeStream()
  const id = useId()
  const [opened, setOpened] = useState(false)
  const unread = useQuery(unreadCountQuery()).data ?? 0
  const list = useInfiniteQuery({ ...notificationsQuery(), enabled: opened })
  const markRead = useMarkRead()
  const items = list.data?.pages.flatMap((page) => page.items) ?? []
  const anchor = { '--_anchor': `--bell-${id.replace(/[^a-zA-Z0-9]/g, '-')}` } as CSSProperties
  const [now, setNow] = useState(Date.now)

  return (
    <span className={styles.root} style={anchor}>
      <button
        type="button"
        className={styles.bell}
        popoverTarget={`${id}-panel`}
        aria-label={unread > 0 ? `Notifications, ${String(unread)} unread` : 'Notifications'}
      >
        <Icon name="bell" />
        {unread > 0 && (
          <span className={styles.badge} aria-hidden="true">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>
      <div
        id={`${id}-panel`}
        popover="auto"
        className={styles.panel}
        role="dialog"
        aria-label="Notifications"
        onToggle={(event) => {
          if (event.newState === 'open') {
            setOpened(true)
            setNow(Date.now())
          }
        }}
      >
        <header className={styles.head}>
          <h2 className={styles.title}>Notifications</h2>
          <Button
            variant="ghost"
            size="sm"
            disabled={unread === 0 || markRead.isPending}
            onClick={() => {
              markRead.mutate('all')
            }}
          >
            Mark all read
          </Button>
        </header>
        {list.isPending ? (
          <p className={styles.empty}>Loading…</p>
        ) : items.length === 0 ? (
          <p className={styles.empty}>
            Nothing yet. You'll hear about assignments, mentions and watched issues here.
          </p>
        ) : (
          <ul role="list" className={styles.list}>
            {items.map((item) => (
              <Item
                key={item.id}
                item={item}
                now={now}
                onOpen={() => {
                  if (item.readAt == null) {
                    markRead.mutate([item.id])
                  }
                }}
              />
            ))}
          </ul>
        )}
        {list.hasNextPage && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => void list.fetchNextPage()}
            disabled={list.isFetchingNextPage}
          >
            Show older
          </Button>
        )}
      </div>
    </span>
  )
}

function Item({ item, now, onOpen }: { item: NotificationView; now: number; onOpen: () => void }) {
  const projectKey = item.issueKey.split('-')[0] ?? ''
  return (
    <li className={styles.item} data-unread={item.readAt == null || undefined}>
      <Link
        to="/p/$projectKey/issues/$issueKey"
        params={{ projectKey, issueKey: item.issueKey }}
        className={styles.link}
        onClick={onOpen}
      >
        <span className={styles.text}>
          <strong>{item.actorName}</strong> {verbs[item.kind] ?? 'updated'}{' '}
          <span className={styles.key}>{item.issueKey}</span>
        </span>
        <span className={styles.summary}>{item.summary}</span>
        <time className={styles.time} dateTime={item.createdAt}>
          {timeAgo(item.createdAt, now)}
        </time>
      </Link>
      {item.readAt == null && <span className="visually-hidden">Unread</span>}
    </li>
  )
}

import { useInfiniteQuery, useQuery, useSuspenseQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { z } from 'zod'
import { meQuery } from '@/features/auth/queries.ts'
import type { HistoryItem, IssueDetails } from '@/shared/api/types.ts'
import { formatMinutes } from '@/shared/lib/durations.ts'
import { timeAgo } from '@/shared/lib/dates.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { Button } from '@/shared/ui/Button.tsx'
import { Tabs } from '@/shared/ui/Tabs.tsx'
import { CommentComposer } from './CommentComposer.tsx'
import { CommentItem } from './CommentItem.tsx'
import { useAddComment } from './mutations.ts'
import { commentsQuery, historyQuery, worklogsQuery } from './queries.ts'
import styles from './Activity.module.css'

type Tab = 'comments' | 'history' | 'worklog'

/** 00-product §3.7: Comments (with @mentions), History, Work log. Each tab loads when it is first shown. */
export function Activity({
  issue,
  canEdit,
  isAdmin,
}: {
  issue: IssueDetails
  canEdit: boolean
  isAdmin: boolean
}) {
  const [tab, setTab] = useState<Tab>('comments')
  return (
    <section aria-label="Activity" className={styles.root}>
      <Tabs
        label="Activity"
        value={tab}
        onChange={setTab}
        tabs={[
          { value: 'comments', label: 'Comments' },
          { value: 'history', label: 'History' },
          { value: 'worklog', label: 'Work log' },
        ]}
      >
        {tab === 'comments' && <Comments issue={issue} canEdit={canEdit} isAdmin={isAdmin} />}
        {tab === 'history' && <History issueKey={issue.key} />}
        {tab === 'worklog' && <WorkLog issueKey={issue.key} />}
      </Tabs>
    </section>
  )
}

// BLOCK 1: comments — the composer on top, newest first under it
function Comments({ issue, canEdit, isAdmin }: { issue: IssueDetails; canEdit: boolean; isAdmin: boolean }) {
  const { data: me } = useSuspenseQuery(meQuery())
  const comments = useInfiniteQuery(commentsQuery(issue.key))
  const add = useAddComment(issue.key)
  const items = comments.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <div className={styles.tab}>
      {canEdit && (
        <CommentComposer
          projectKey={issue.projectKey}
          label={`Comment on ${issue.key}`}
          submitLabel="Comment"
          onSubmit={(bodyHtml) => add.mutateAsync(bodyHtml)}
        />
      )}
      {comments.isPending && <p className={styles.muted}>Loading comments…</p>}
      {comments.isError && <p className={styles.muted}>Couldn't load the comments.</p>}
      {comments.isSuccess && items.length === 0 && <p className={styles.muted}>No comments yet.</p>}
      <ol role="list" className={styles.list} aria-label="Comments">
        {items.map((comment) => (
          <CommentItem
            key={comment.id}
            comment={comment}
            issueKey={issue.key}
            projectKey={issue.projectKey}
            canEdit={canEdit && comment.author.id === me.id}
            canDelete={canEdit && (comment.author.id === me.id || isAdmin)}
          />
        ))}
      </ol>
      {comments.hasNextPage && (
        <Button
          size="sm"
          disabled={comments.isFetchingNextPage}
          onClick={() => void comments.fetchNextPage()}
        >
          Show older comments
        </Button>
      )}
    </div>
  )
}

// BLOCK 2: history — issue_event rows as sentences
function History({ issueKey }: { issueKey: string }) {
  const history = useInfiniteQuery(historyQuery(issueKey))
  const items = history.data?.pages.flatMap((page) => page.items) ?? []
  return (
    <div className={styles.tab}>
      {history.isPending && <p className={styles.muted}>Loading history…</p>}
      <ol role="list" className={styles.list} aria-label="History">
        {items.map((item) => (
          <li key={item.id} className={styles.item}>
            <Avatar
              name={item.actor.displayName}
              initials={item.actor.initials}
              color={avatarColor(item.actor.avatarColor)}
              size="sm"
            />
            <p className={styles.sentence}>
              <strong>{item.actor.displayName}</strong> {describeChange(item)}{' '}
              <time dateTime={item.occurredAt} className={styles.muted}>
                {timeAgo(item.occurredAt)}
              </time>
            </p>
          </li>
        ))}
      </ol>
      {history.hasNextPage && (
        <Button size="sm" disabled={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>
          Show older
        </Button>
      )}
    </div>
  )
}

// BLOCK 3: work log
function WorkLog({ issueKey }: { issueKey: string }) {
  const worklogs = useQuery(worklogsQuery(issueKey))
  return (
    <div className={styles.tab}>
      {worklogs.isPending && <p className={styles.muted}>Loading work log…</p>}
      {worklogs.data?.length === 0 && <p className={styles.muted}>No work logged yet.</p>}
      <ol role="list" className={styles.list} aria-label="Work log">
        {worklogs.data?.map((log) => (
          <li key={log.id} className={styles.item}>
            <Avatar
              name={log.user.displayName}
              initials={log.user.initials}
              color={avatarColor(log.user.avatarColor)}
              size="sm"
            />
            <div>
              <p className={styles.sentence}>
                <strong>{log.user.displayName}</strong> logged <strong>{formatMinutes(log.minutes)}</strong>{' '}
                <time dateTime={log.startedAt} className={styles.muted}>
                  {timeAgo(log.startedAt)}
                </time>
              </p>
              {log.note && <p className={styles.note}>{log.note}</p>}
            </div>
          </li>
        ))}
      </ol>
    </div>
  )
}

// a stored value is JSON as written (V008): a string, number, list, or an object with a name
const named = z.object({ name: z.string() })
const keyed = z.object({ key: z.string() })

function valueText(value: unknown): string {
  if (value === null || value === undefined || value === '') return 'None'
  if (Array.isArray(value)) return value.length === 0 ? 'None' : value.map(valueText).join(', ')
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean')
    return String(value)
  const withName = named.safeParse(value)
  if (withName.success) return withName.data.name
  const withKey = keyed.safeParse(value)
  return withKey.success ? withKey.data.key : 'a value'
}

const FIELD_NAMES: Record<string, string> = {
  summary: 'the summary',
  description: 'the description',
  assigneeId: 'the assignee',
  storyPoints: 'story points',
  dueDate: 'the due date',
  startDate: 'the start date',
  fixVersion: 'the fix version',
  originalEstimateMin: 'the estimate',
  parentKey: 'the parent',
  parentId: 'the parent',
}

/** One history row as the rest of a sentence that starts with the actor's name. */
export function describeChange(item: HistoryItem): string {
  const field = FIELD_NAMES[item.field ?? ''] ?? item.field ?? 'a field'
  switch (item.kind) {
    case 'created':
      return 'created the issue'
    case 'transitioned':
      return `changed the status from ${valueText(item.oldValue)} to ${valueText(item.newValue)}`
    case 'moved':
      return item.field === 'sprint' ? 'moved it to another sprint' : 'changed its rank'
    case 'field_changed':
      if (item.field === 'originalEstimateMin') {
        return `set the estimate to ${typeof item.newValue === 'number' ? formatMinutes(item.newValue) : 'None'}`
      }
      if (item.field === 'description' || item.field === 'summary') {
        return `changed ${field}`
      }
      return `changed ${field} from ${valueText(item.oldValue)} to ${valueText(item.newValue)}`
    case 'commented':
      return 'added a comment'
    case 'linked':
      return item.newValue
        ? `linked: ${item.field ?? ''} ${valueText(item.newValue)}`
        : `removed a link: ${item.field ?? ''} ${valueText(item.oldValue)}`
    case 'logged_work':
      return 'logged work'
    case 'deleted':
      return 'deleted the issue'
    default:
      return 'updated the issue'
  }
}

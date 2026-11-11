import { useQuery, useSuspenseQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { meQuery } from '@/features/auth/queries.ts'
import { backlogQuery } from '@/features/backlog/queries.ts'
import { projectQuery } from '@/features/projects/queries.ts'
import type { IssueDetails, IssuePriority, UserRef } from '@/shared/api/types.ts'
import { dayLabel } from '@/shared/lib/dates.ts'
import { text } from '@/shared/lib/forms.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { Button } from '@/shared/ui/Button.tsx'
import { PriorityIcon } from '@/shared/ui/PriorityIcon.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { InlineField } from './InlineField.tsx'
import { usePatchIssue } from './mutations.ts'
import styles from './DetailsPanel.module.css'

const PRIORITIES: readonly IssuePriority[] = ['highest', 'high', 'medium', 'low', 'lowest']

/**
 * 00-product §3.7 "Details": every field a row, every editable row an inline editor in a popover. Each save is
 * one merge patch with only that field, sent with If-Match (usePatchIssue), and shown at once (optimistic).
 */
export function DetailsPanel({ issue, canEdit }: { issue: IssueDetails; canEdit: boolean }) {
  const { data: me } = useSuspenseQuery(meQuery())
  const { data: project } = useQuery(projectQuery(issue.projectKey))
  // the epic picker's options; cached for a minute, so opening issue after issue doesn't reload the backlog
  const { data: backlog } = useQuery({ ...backlogQuery(issue.projectKey), staleTime: 60_000 })
  const patch = usePatchIssue(issue.key)

  // only members can be assigned (viewers can't edit issues, so they can't own one either)
  const assignable = (project?.members ?? []).filter((member) => member.role !== 'viewer')
  const userRef = (id: string): UserRef | null => {
    const member = assignable.find((m) => m.userId === id)
    return member
      ? { id, displayName: member.displayName, initials: member.initials, avatarColor: member.avatarColor }
      : null
  }
  const epics = backlog?.epics ?? []
  const canHaveEpic = issue.type !== 'epic' && issue.type !== 'subtask'

  return (
    <dl className={styles.root}>
      {/* BLOCK 1: people */}
      <InlineField
        label="Assignee"
        value={<Person user={issue.assignee ?? null} empty="Unassigned" />}
        canEdit={canEdit}
        editor={() => (
          <Select name="assigneeId" defaultValue={issue.assignee?.id ?? ''} aria-label="Assignee">
            <option value="">Unassigned</option>
            {assignable.map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.displayName}
              </option>
            ))}
          </Select>
        )}
        onSave={(form) => {
          const id = text(form, 'assigneeId')
          patch.mutate({
            patch: { assigneeId: id || null },
            optimistic: { assignee: id ? userRef(id) : null },
          })
        }}
        extraActions={(close) =>
          issue.assignee?.id !== me.id && assignable.some((m) => m.userId === me.id) ? (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                patch.mutate({ patch: { assigneeId: me.id }, optimistic: { assignee: userRef(me.id) } })
                close()
              }}
            >
              Assign to me
            </Button>
          ) : null
        }
      />
      <InlineField
        label="Reporter"
        value={<Person user={issue.reporter} empty="" />}
        canEdit={false}
        editor={() => null}
        onSave={() => undefined}
      />

      {/* BLOCK 2: planning */}
      <InlineField
        label="Priority"
        value={
          <span className={styles.inline}>
            <PriorityIcon priority={issue.priority} />
            {capitalise(issue.priority)}
          </span>
        }
        canEdit={canEdit}
        editor={() => (
          <Select name="priority" defaultValue={issue.priority} aria-label="Priority">
            {PRIORITIES.map((value) => (
              <option key={value} value={value}>
                {capitalise(value)}
              </option>
            ))}
          </Select>
        )}
        onSave={(form) => {
          const value = text(form, 'priority') as IssuePriority
          patch.mutate({ patch: { priority: value }, optimistic: { priority: value } })
        }}
      />
      <InlineField
        label="Story points"
        value={issue.storyPoints ?? <span className={styles.muted}>None</span>}
        canEdit={canEdit && issue.type !== 'epic'}
        editor={() => (
          <TextInput
            name="storyPoints"
            type="number"
            min={0}
            max={100}
            step={0.5}
            inputMode="decimal"
            defaultValue={issue.storyPoints ?? ''}
            aria-label="Story points"
          />
        )}
        onSave={(form) => {
          const raw = text(form, 'storyPoints').trim()
          const value = raw === '' ? null : Number(raw)
          if (value !== null && (Number.isNaN(value) || value < 0 || value > 100)) {
            return false
          }
          patch.mutate({ patch: { storyPoints: value }, optimistic: { storyPoints: value } })
        }}
      />
      <InlineField
        label="Sprint"
        value={issue.sprint?.name ?? <span className={styles.muted}>Backlog</span>}
        canEdit={false}
        editor={() => null}
        onSave={() => undefined}
      />
      {canHaveEpic && (
        <InlineField
          label="Epic"
          value={
            issue.parent ? (
              <Link
                to="/p/$projectKey/issues/$issueKey"
                params={{ projectKey: issue.projectKey, issueKey: issue.parent.key }}
              >
                {issue.parent.summary}
              </Link>
            ) : (
              <span className={styles.muted}>None</span>
            )
          }
          canEdit={canEdit}
          editor={() => (
            <Select name="parentKey" defaultValue={issue.parent?.key ?? ''} aria-label="Epic">
              <option value="">None</option>
              {epics.map((epic) => (
                <option key={epic.id} value={epic.key}>
                  {epic.key} · {epic.summary}
                </option>
              ))}
            </Select>
          )}
          onSave={(form) => {
            const key = text(form, 'parentKey')
            const epic = epics.find((e) => e.key === key)
            patch.mutate({
              patch: { parentKey: key || null },
              optimistic: {
                parent: epic
                  ? {
                      id: epic.id,
                      key: epic.key,
                      summary: epic.summary,
                      type: 'epic',
                      statusCategory: 'todo',
                      statusName: '',
                    }
                  : null,
              },
            })
          }}
        />
      )}
      {issue.type === 'subtask' && issue.parent && (
        <InlineField
          label="Parent"
          value={
            <Link
              to="/p/$projectKey/issues/$issueKey"
              params={{ projectKey: issue.projectKey, issueKey: issue.parent.key }}
            >
              {issue.parent.key} · {issue.parent.summary}
            </Link>
          }
          canEdit={false}
          editor={() => null}
          onSave={() => undefined}
        />
      )}
      <InlineField
        label="Labels"
        value={
          issue.labels.length > 0 ? (
            <ul role="list" className={styles.labels}>
              {issue.labels.map((label) => (
                <li key={label} className={styles.label}>
                  {label}
                </li>
              ))}
            </ul>
          ) : (
            <span className={styles.muted}>None</span>
          )
        }
        canEdit={canEdit}
        editor={() => (
          <TextInput
            name="labels"
            defaultValue={issue.labels.join(' ')}
            placeholder="frontend auth"
            aria-label="Labels, separated by spaces or commas"
          />
        )}
        onSave={(form) => {
          const labels = [
            ...new Set(
              text(form, 'labels')
                .split(/[\s,]+/)
                .filter(Boolean),
            ),
          ]
          patch.mutate({ patch: { labels }, optimistic: { labels } })
        }}
      />
      <InlineField
        label="Due date"
        value={issue.dueDate ? dayLabel(issue.dueDate) : <span className={styles.muted}>None</span>}
        canEdit={canEdit}
        editor={() => (
          <TextInput name="dueDate" type="date" defaultValue={issue.dueDate ?? ''} aria-label="Due date" />
        )}
        onSave={(form) => {
          const value = text(form, 'dueDate') || null
          patch.mutate({ patch: { dueDate: value }, optimistic: { dueDate: value } })
        }}
      />
      <InlineField
        label="Fix version"
        value={issue.fixVersion ?? <span className={styles.muted}>None</span>}
        canEdit={canEdit}
        editor={() => (
          <TextInput
            name="fixVersion"
            maxLength={50}
            defaultValue={issue.fixVersion ?? ''}
            aria-label="Fix version"
          />
        )}
        onSave={(form) => {
          const value = text(form, 'fixVersion').trim() || null
          patch.mutate({ patch: { fixVersion: value }, optimistic: { fixVersion: value } })
        }}
      />
    </dl>
  )
}

function Person({ user, empty }: { user: UserRef | null; empty: string }) {
  if (!user) {
    return <span className={styles.muted}>{empty}</span>
  }
  return (
    <span className={styles.inline}>
      <Avatar
        name={user.displayName}
        initials={user.initials}
        color={avatarColor(user.avatarColor)}
        size="sm"
        decorative
      />
      {user.displayName}
    </span>
  )
}

function capitalise(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1)
}

import { useSuspenseQuery } from '@tanstack/react-query'
import { useState, type SubmitEvent } from 'react'
import { projectQuery } from '@/features/projects/queries.ts'
import type { IssueDetails } from '@/shared/api/types.ts'
import { focusOnMount } from '@/shared/lib/focus.ts'
import { text } from '@/shared/lib/forms.ts'
import { timeAgo } from '@/shared/lib/dates.ts'
import { SafeHtml } from '@/shared/ui/SafeHtml.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { Activity } from './Activity.tsx'
import { Attachments } from './Attachments.tsx'
import { DetailsPanel } from './DetailsPanel.tsx'
import { IssueHeader } from './IssueHeader.tsx'
import { Links } from './Links.tsx'
import { usePatchIssue } from './mutations.ts'
import { issueQuery } from './queries.ts'
import { StatusButton } from './StatusButton.tsx'
import { Subtasks } from './Subtasks.tsx'
import { TimeTracking } from './TimeTracking.tsx'
import styles from './IssueView.module.css'

/**
 * The issue, wherever it is shown: the side panel over the board (IssuePanel) and the full page (IssuePage)
 * render this same component. It is a size container: the layout follows the space it gets — one column in
 * the panel, content plus a details column on a wide page — not the window's width.
 */
export function IssueView({ issueKey, onClose }: { issueKey: string; onClose?: () => void }) {
  const { data: issue } = useSuspenseQuery(issueQuery(issueKey))
  const { data: project } = useSuspenseQuery(projectQuery(issue.projectKey))
  const canEdit = project.myRole !== 'viewer'
  const isAdmin = project.myRole === 'admin'
  const Heading = onClose ? 'h2' : 'h1'

  return (
    <article className={styles.root} aria-labelledby={`${issue.key}-title`}>
      <IssueHeader issue={issue} project={project} onClose={onClose} />
      <Summary issue={issue} canEdit={canEdit} as={Heading} />

      <div className={styles.layout}>
        {/* BLOCK 1: what the issue is about */}
        <div className={styles.main}>
          <section aria-labelledby={`${issue.key}-description`} className={styles.description}>
            <h3 id={`${issue.key}-description`} className={styles.sectionTitle}>
              Description
            </h3>
            {/* read-only until phase 16 makes it a collaborative editor */}
            {issue.descriptionHtml ? (
              <SafeHtml html={issue.descriptionHtml} />
            ) : (
              <p className={styles.muted}>No description.</p>
            )}
          </section>
          <Attachments issue={issue} canEdit={canEdit} isAdmin={isAdmin} />
          <Subtasks issue={issue} canEdit={canEdit} />
          <Links issue={issue} canEdit={canEdit} />
          <Activity issue={issue} canEdit={canEdit} isAdmin={isAdmin} />
        </div>

        {/* BLOCK 2: status and fields */}
        <aside className={styles.side} aria-label="Details">
          <StatusButton issue={issue} canEdit={canEdit} />
          <DetailsPanel issue={issue} canEdit={canEdit} />
          <TimeTracking issue={issue} canEdit={canEdit} />
          <p className={styles.stamps}>
            Created {timeAgo(issue.createdAt)} · Updated {timeAgo(issue.updatedAt)}
          </p>
        </aside>
      </div>
    </article>
  )
}

/** The summary as the heading; members click it to edit in place (Enter saves, Esc cancels). */
function Summary({
  issue,
  canEdit,
  as: Heading,
}: {
  issue: IssueDetails
  canEdit: boolean
  as: 'h1' | 'h2'
}) {
  const [editing, setEditing] = useState(false)
  const patch = usePatchIssue(issue.key)

  const save = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault()
    const summary = text(new FormData(event.currentTarget), 'summary').trim()
    if (summary && summary !== issue.summary) {
      patch.mutate({ patch: { summary }, optimistic: { summary } })
    }
    setEditing(false)
  }

  if (editing) {
    return (
      <form onSubmit={save} className={styles.summaryForm}>
        <TextInput
          name="summary"
          defaultValue={issue.summary}
          maxLength={255}
          required
          ref={focusOnMount}
          aria-label="Summary"
          className={styles.summaryInput}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.preventDefault()
              event.stopPropagation()
              setEditing(false)
            }
          }}
          onBlur={(event) => event.currentTarget.form?.requestSubmit()}
        />
      </form>
    )
  }

  return (
    <Heading id={`${issue.key}-title`} className={styles.summary} tabIndex={-1}>
      {canEdit ? (
        <button
          type="button"
          className={styles.summaryButton}
          title="Edit summary"
          onClick={() => setEditing(true)}
        >
          {issue.summary}
        </button>
      ) : (
        issue.summary
      )}
    </Heading>
  )
}

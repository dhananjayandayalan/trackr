import { Link } from '@tanstack/react-router'
import { useActionState, useState } from 'react'
import { messageOf } from '@/shared/api/errors.ts'
import type { IssueDetails, IssueRef } from '@/shared/api/types.ts'
import { issueType } from '@/shared/lib/enums.ts'
import { focusOnMount } from '@/shared/lib/focus.ts'
import { text } from '@/shared/lib/forms.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { FormAlert, SubmitButton } from '@/shared/ui/Form.tsx'
import { Lozenge } from '@/shared/ui/Lozenge.tsx'
import { Progress } from '@/shared/ui/Progress.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { TypeIcon } from '@/shared/ui/TypeIcon.tsx'
import { useCreateIssue } from './mutations.ts'
import styles from './IssueSections.module.css'

/**
 * A story's subtasks, or an epic's child issues — the same list (issue.subtasks are the children by rank),
 * with progress = share of children in a done status. A subtask has no children of its own (00-product §4.2).
 */
export function Subtasks({ issue, canEdit }: { issue: IssueDetails; canEdit: boolean }) {
  const [adding, setAdding] = useState(false)
  if (issue.type === 'subtask') {
    return null
  }
  const children = issue.subtasks
  const done = children.filter((child) => child.statusCategory === 'done').length
  const title = issue.type === 'epic' ? 'Child issues' : 'Subtasks'

  return (
    <section className={styles.section} aria-labelledby={`${issue.key}-subtasks`}>
      <header className={styles.sectionHeader}>
        <h3 id={`${issue.key}-subtasks`} className={styles.sectionTitle}>
          {title}
        </h3>
        {canEdit && issue.type !== 'epic' && !adding && (
          <Button size="sm" variant="ghost" icon="plus" onClick={() => setAdding(true)}>
            Add subtask
          </Button>
        )}
      </header>
      {children.length > 0 && (
        <div className={styles.progress}>
          <Progress
            value={Math.round((done / children.length) * 100)}
            label={`${String(done)} of ${String(children.length)} done`}
          />
          <span className={styles.muted}>
            {done} of {children.length} done
          </span>
        </div>
      )}
      {children.length > 0 && (
        <ul role="list" className={styles.list}>
          {children.map((child) => (
            <ChildRow key={child.id} projectKey={issue.projectKey} child={child} />
          ))}
        </ul>
      )}
      {children.length === 0 && !adding && <p className={styles.muted}>None yet.</p>}
      {adding && <AddSubtask parent={issue} onDone={() => setAdding(false)} />}
    </section>
  )
}

function ChildRow({ projectKey, child }: { projectKey: string; child: IssueRef }) {
  return (
    <li className={styles.row} data-done={child.statusCategory === 'done' || undefined}>
      <TypeIcon type={issueType(child.type)} />
      <Link
        to="/p/$projectKey/issues/$issueKey"
        params={{ projectKey, issueKey: child.key }}
        className={styles.rowKey}
      >
        {child.key}
      </Link>
      <span className={styles.rowSummary}>{child.summary}</span>
      <Lozenge
        tone={
          child.statusCategory === 'done'
            ? 'done'
            : child.statusCategory === 'in_progress'
              ? 'progress'
              : 'todo'
        }
      >
        {child.statusName}
      </Lozenge>
    </li>
  )
}

function AddSubtask({ parent, onDone }: { parent: IssueDetails; onDone: () => void }) {
  const create = useCreateIssue()
  // a form action: React resets the form after it succeeds, ready for the next subtask
  const [error, action] = useActionState(async (_previous: string | undefined, form: FormData) => {
    const summary = text(form, 'summary').trim()
    if (!summary) {
      return 'Enter a summary.'
    }
    try {
      await create.mutateAsync({
        projectKey: parent.projectKey,
        type: 'subtask',
        summary,
        parentKey: parent.key,
      })
      return undefined
    } catch (failure) {
      return messageOf(failure)
    }
  }, undefined)

  return (
    <form action={action} className={styles.inlineForm} noValidate>
      <FormAlert message={error} />
      <TextInput
        name="summary"
        maxLength={255}
        placeholder="What needs to be done?"
        aria-label="Subtask summary"
        ref={focusOnMount}
      />
      <SubmitButton pendingLabel="Adding…">Add</SubmitButton>
      <Button variant="ghost" onClick={onDone}>
        Done
      </Button>
    </form>
  )
}

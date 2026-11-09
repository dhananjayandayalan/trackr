import { useActionState, useEffect, useId, useRef, useState } from 'react'
import { messageOf } from '@/shared/api/errors.ts'
import type { IssueTypeValue } from '@/shared/api/types.ts'
import { text } from '@/shared/lib/forms.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { useCreateBacklogIssue } from './mutations.ts'
import styles from './InlineCreate.module.css'

/**
 * "+ Create issue" at the bottom of a list (00-product §3.6): a type and a summary, Enter to add, and the
 * form stays open for the next one. The full dialog with every field comes in phase 15.
 */
export function InlineCreate({ projectKey, sprintId }: { projectKey: string; sprintId: string | null }) {
  const [open, setOpen] = useState(false)
  const create = useCreateBacklogIssue(projectKey)
  const summaryId = useId()
  const typeId = useId()
  const summary = useRef<HTMLInputElement>(null)

  // the user just asked for this field: take them to it — once, when it appears (not on every re-render)
  useEffect(() => {
    if (open) {
      summary.current?.focus()
    }
  }, [open])

  async function add(_previous: { message?: string; type: IssueTypeValue }, form: FormData) {
    const type = (text(form, 'type') || 'story') as IssueTypeValue
    try {
      await create.mutateAsync({ summary: text(form, 'summary').trim(), type, sprintId })
      return { type } // the form resets for the next issue; the type is kept
    } catch (error) {
      return { message: messageOf(error), type }
    }
  }
  const [state, formAction, pending] = useActionState(add, { type: 'story' as IssueTypeValue })

  if (!open) {
    return (
      <Button
        variant="ghost"
        size="sm"
        icon="plus"
        className={styles.open}
        onClick={() => {
          setOpen(true)
        }}
      >
        Create issue
      </Button>
    )
  }

  return (
    <form action={formAction} className={styles.form}>
      <label htmlFor={typeId} className="visually-hidden">
        Issue type
      </label>
      <Select id={typeId} name="type" defaultValue={state.type}>
        <option value="story">Story</option>
        <option value="task">Task</option>
        <option value="bug">Bug</option>
      </Select>
      <label htmlFor={summaryId} className="visually-hidden">
        Summary
      </label>
      <TextInput
        id={summaryId}
        name="summary"
        required
        maxLength={255}
        placeholder="What needs to be done?"
        ref={summary}
        className={styles.summary}
        onKeyDown={(event) => {
          if (event.key === 'Escape') setOpen(false)
        }}
      />
      <Button type="submit" variant="primary" size="sm" disabled={pending}>
        {pending ? 'Adding…' : 'Add'}
      </Button>
      <Button
        variant="ghost"
        size="sm"
        onClick={() => {
          setOpen(false)
        }}
      >
        Cancel
      </Button>
      {state.message && (
        <p role="alert" className={styles.error}>
          {state.message}
        </p>
      )}
    </form>
  )
}

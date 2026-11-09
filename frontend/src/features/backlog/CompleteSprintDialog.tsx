import { useQuery } from '@tanstack/react-query'
import { useActionState, useId } from 'react'
import { messageOf } from '@/shared/api/errors.ts'
import { text } from '@/shared/lib/forms.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Dialog } from '@/shared/ui/Dialog.tsx'
import { FormActions, FormAlert, SubmitButton } from '@/shared/ui/Form.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { useToast } from '@/shared/ui/Toast.tsx'
import { useCompleteSprint } from './mutations.ts'
import { backlogQuery } from './queries.ts'
import styles from './SprintDialogs.module.css'

/**
 * Complete the active sprint (00-product §3.5, §4.5): its open issues go to the backlog or a planned sprint.
 * Opened from the board and from the backlog. The planned sprints come from the backlog query — cached if
 * the backlog was visited, fetched now if not.
 */
export function CompleteSprintDialog({
  open,
  onClose,
  projectKey,
  sprint,
}: {
  open: boolean
  onClose: () => void
  projectKey: string
  sprint: { id: string; name: string }
}) {
  return (
    <Dialog open={open} onClose={onClose} title={`Complete ${sprint.name}`}>
      <CompleteSprintForm projectKey={projectKey} sprint={sprint} onDone={onClose} />
    </Dialog>
  )
}

function CompleteSprintForm({
  projectKey,
  sprint,
  onDone,
}: {
  projectKey: string
  sprint: { id: string; name: string }
  onDone: () => void
}) {
  const { data: backlog } = useQuery(backlogQuery(projectKey))
  const complete = useCompleteSprint(projectKey)
  const toast = useToast()
  const targetId = useId()

  const rows = backlog?.activeSprint?.sprint.id === sprint.id ? backlog.activeSprint.issues : []
  const open = rows.filter((row) => row.statusCategory !== 'done').length
  const done = rows.length - open

  async function submit(_previous: { message?: string }, form: FormData): Promise<{ message?: string }> {
    try {
      const report = await complete.mutateAsync({
        sprintId: sprint.id,
        moveOpenIssuesTo: text(form, 'moveOpenIssuesTo') || 'backlog',
      })
      toast(
        `${sprint.name} completed: ${report.completedPoints} of ${report.committedPoints} points done.`,
        'success',
      )
      onDone()
      return {}
    } catch (error) {
      return { message: messageOf(error) }
    }
  }

  const [state, formAction] = useActionState(submit, {})

  return (
    <form action={formAction} className={styles.form}>
      <FormAlert message={state.message} />
      {backlog && (
        <p className={styles.summary}>
          <strong>{done}</strong> {done === 1 ? 'issue is' : 'issues are'} done; <strong>{open}</strong> still
          open.
        </p>
      )}
      {/* with nothing open there's nothing to move: the select would only be a question with no meaning */}
      {open > 0 ? (
        <div className={styles.field}>
          <label htmlFor={targetId}>Move the open issues to</label>
          <Select id={targetId} name="moveOpenIssuesTo" defaultValue="backlog">
            <option value="backlog">The backlog</option>
            {backlog?.plannedSprints.map((list) => (
              <option key={list.sprint.id} value={list.sprint.id}>
                {list.sprint.name}
              </option>
            ))}
          </Select>
        </div>
      ) : (
        <input type="hidden" name="moveOpenIssuesTo" value="backlog" />
      )}
      <FormActions>
        <Button variant="ghost" onClick={onDone}>
          Cancel
        </Button>
        <SubmitButton pendingLabel="Completing…">Complete sprint</SubmitButton>
      </FormActions>
    </form>
  )
}

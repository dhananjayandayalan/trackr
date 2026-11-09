import { useActionState, useState } from 'react'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import type { SprintView } from '@/shared/api/types.ts'
import { isoDay } from '@/shared/lib/dates.ts'
import { text } from '@/shared/lib/forms.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Dialog } from '@/shared/ui/Dialog.tsx'
import { Field } from '@/shared/ui/Field.tsx'
import { FormActions, FormAlert, SubmitButton } from '@/shared/ui/Form.tsx'
import { Textarea, TextInput } from '@/shared/ui/TextInput.tsx'
import { useToast } from '@/shared/ui/Toast.tsx'
import { useStartSprint } from './mutations.ts'
import styles from './SprintDialogs.module.css'

interface FormState {
  errors: Record<string, string>
  message?: string
}

/** Start a planned sprint (00-product §4.5: a name and both dates are required). Two weeks by default. */
export function StartSprintDialog({
  open,
  onClose,
  projectKey,
  sprint,
  issueCount,
}: {
  open: boolean
  onClose: () => void
  projectKey: string
  sprint: SprintView
  issueCount: number
}) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={`Start ${sprint.name}`}
      description={`${issueCount} ${issueCount === 1 ? 'issue' : 'issues'} will be on the board.`}
    >
      <StartSprintForm projectKey={projectKey} sprint={sprint} onDone={onClose} />
    </Dialog>
  )
}

function StartSprintForm({
  projectKey,
  sprint,
  onDone,
}: {
  projectKey: string
  sprint: SprintView
  onDone: () => void
}) {
  const start = useStartSprint(projectKey)
  const toast = useToast()
  // the end date may not be before the start: the input's `min` follows the start as it changes
  const [startsOn, setStartsOn] = useState(sprint.startsOn ?? isoDay())

  async function submit(_previous: FormState, form: FormData): Promise<FormState> {
    try {
      const goal = text(form, 'goal').trim()
      await start.mutateAsync({
        sprintId: sprint.id,
        name: text(form, 'name').trim(),
        goal: goal === '' ? null : goal,
        startsOn: text(form, 'startsOn'),
        endsOn: text(form, 'endsOn'),
      })
      toast(`${text(form, 'name')} started.`, 'success')
      onDone()
      return { errors: {} }
    } catch (error) {
      if (isApiError(error, 'SPRINT_ALREADY_ACTIVE')) {
        return { errors: {}, message: 'Another sprint is already active. Complete it first.' }
      }
      return { errors: isApiError(error) ? error.fieldErrors : {}, message: messageOf(error) }
    }
  }

  const [state, formAction] = useActionState(submit, { errors: {} })

  return (
    <form action={formAction} className={styles.form}>
      <FormAlert message={state.message} />
      <Field label="Sprint name" error={state.errors.name}>
        {(control) => (
          <TextInput {...control} name="name" required maxLength={80} defaultValue={sprint.name} />
        )}
      </Field>
      <Field label="Goal" hint="Optional: what this sprint is for, in a sentence." error={state.errors.goal}>
        {(control) => (
          <Textarea {...control} name="goal" maxLength={500} rows={2} defaultValue={sprint.goal ?? ''} />
        )}
      </Field>
      <div className={styles.dates}>
        <Field label="Starts" error={state.errors.startsOn}>
          {(control) => (
            <TextInput
              {...control}
              name="startsOn"
              type="date"
              required
              value={startsOn}
              onChange={(event) => {
                setStartsOn(event.target.value)
              }}
            />
          )}
        </Field>
        <Field label="Ends" error={state.errors.endsOn}>
          {(control) => (
            <TextInput
              {...control}
              name="endsOn"
              type="date"
              required
              min={startsOn}
              defaultValue={sprint.endsOn ?? isoDay(14)}
            />
          )}
        </Field>
      </div>
      <FormActions>
        <Button variant="ghost" onClick={onDone}>
          Cancel
        </Button>
        <SubmitButton pendingLabel="Starting…">Start sprint</SubmitButton>
      </FormActions>
    </form>
  )
}

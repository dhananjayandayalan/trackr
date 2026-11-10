import { useActionState, useId, useRef } from 'react'
import { messageOf } from '@/shared/api/errors.ts'
import type { IssueDetails } from '@/shared/api/types.ts'
import { formatMinutes, parseDuration } from '@/shared/lib/durations.ts'
import { text } from '@/shared/lib/forms.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Field } from '@/shared/ui/Field.tsx'
import { FormActions, FormAlert, SubmitButton } from '@/shared/ui/Form.tsx'
import { Progress } from '@/shared/ui/Progress.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { InlineField } from './InlineField.tsx'
import { useLogWork, usePatchIssue } from './mutations.ts'
import styles from './IssueSections.module.css'

/** Estimate, logged, remaining (00-product §3.7), and the two ways to change them: edit the estimate, log work. */
export function TimeTracking({ issue, canEdit }: { issue: IssueDetails; canEdit: boolean }) {
  const { originalEstimateMin: estimate, timeSpentMin: spent, remainingMin: remaining } = issue.timeTracking
  const patch = usePatchIssue(issue.key)
  // with an estimate, the bar is spent / (spent + remaining); without one it's full once anything is logged
  const total = estimate == null ? spent : spent + (remaining ?? 0)
  const percent = total === 0 ? 0 : (spent / total) * 100

  return (
    <section className={styles.section} aria-labelledby={`${issue.key}-time`}>
      <header className={styles.sectionHeader}>
        <h3 id={`${issue.key}-time`} className={styles.sectionTitle}>
          Time tracking
        </h3>
        {canEdit && <LogWork issue={issue} />}
      </header>
      <Progress value={percent} label={`${formatMinutes(spent)} logged`} />
      <p className={styles.timeLine}>
        <span>{formatMinutes(spent)} logged</span>
        <span>{remaining == null ? 'No estimate' : `${formatMinutes(remaining)} remaining`}</span>
      </p>
      <dl>
        <InlineField
          label="Estimate"
          value={estimate == null ? <span className={styles.muted}>None</span> : formatMinutes(estimate)}
          canEdit={canEdit}
          editor={() => (
            <TextInput
              name="estimate"
              defaultValue={estimate == null ? '' : formatMinutes(estimate)}
              placeholder="2h 30m"
              aria-label="Original estimate, e.g. 2h 30m"
            />
          )}
          onSave={(form) => {
            const raw = text(form, 'estimate').trim()
            const minutes = raw === '' ? null : parseDuration(raw)
            if (raw !== '' && minutes === null) {
              return false
            }
            patch.mutate({
              patch: { originalEstimateMin: minutes },
              optimistic: {
                timeTracking: {
                  originalEstimateMin: minutes,
                  timeSpentMin: spent,
                  remainingMin: minutes == null ? null : Math.max(0, minutes - spent),
                },
              },
            })
          }}
        />
      </dl>
    </section>
  )
}

interface LogState {
  errors: Record<string, string>
  message?: string
}

/** "Log work": a popover form — how long, when, a note. */
function LogWork({ issue }: { issue: IssueDetails }) {
  const id = useId()
  const popover = useRef<HTMLDivElement>(null)
  const log = useLogWork(issue.key)

  const [state, action] = useActionState(
    async (_previous: LogState, form: FormData): Promise<LogState> => {
      const minutes = parseDuration(text(form, 'duration'))
      if (minutes === null) {
        return { errors: { duration: 'Write it like 1h 30m, 45m or 2d.' } }
      }
      const note = text(form, 'note').trim()
      try {
        await log.mutateAsync({ startedAt: new Date().toISOString(), minutes, note: note || null })
        if (typeof popover.current?.hidePopover === 'function') {
          popover.current.hidePopover()
        }
        return { errors: {} }
      } catch (error) {
        return { errors: {}, message: messageOf(error) }
      }
    },
    { errors: {} },
  )

  return (
    <>
      <Button size="sm" variant="ghost" icon="clock" popoverTarget={`${id}-log`}>
        Log work
      </Button>
      <div
        ref={popover}
        id={`${id}-log`}
        popover="auto"
        className={styles.popoverForm}
        // opened: straight into "Time spent"
        onToggle={(event) => {
          if (event.newState === 'open') {
            event.currentTarget.querySelector<HTMLInputElement>('input[name="duration"]')?.focus()
          }
        }}
      >
        <form action={action} className={styles.stack} noValidate aria-label={`Log work on ${issue.key}`}>
          <FormAlert message={state.message} />
          <Field label="Time spent" hint="e.g. 1h 30m" error={state.errors.duration}>
            {(control) => <TextInput {...control} name="duration" required />}
          </Field>
          <Field label="Note">{(control) => <TextInput {...control} name="note" maxLength={2000} />}</Field>
          <FormActions>
            <SubmitButton pendingLabel="Logging…">Log</SubmitButton>
          </FormActions>
        </form>
      </div>
    </>
  )
}

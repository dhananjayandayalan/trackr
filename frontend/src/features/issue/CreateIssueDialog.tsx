import { useQuery, useSuspenseQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from '@tanstack/react-router'
import { startTransition, useActionState, useEffect, useRef, useState } from 'react'
import { meQuery } from '@/features/auth/queries.ts'
import { backlogQuery } from '@/features/backlog/queries.ts'
import { projectQuery, projectsQuery } from '@/features/projects/queries.ts'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import type { IssuePriority, IssueTypeValue } from '@/shared/api/types.ts'
import { text } from '@/shared/lib/forms.ts'
import { useKeyboardShortcut } from '@/shared/lib/shortcuts.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Dialog } from '@/shared/ui/Dialog.tsx'
import { Field } from '@/shared/ui/Field.tsx'
import { FormActions, FormAlert } from '@/shared/ui/Form.tsx'
import { Segmented } from '@/shared/ui/Segmented.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { Textarea, TextInput } from '@/shared/ui/TextInput.tsx'
import { useToast } from '@/shared/ui/Toast.tsx'
import {
  closeCreateIssue,
  openCreateIssue,
  useCreateIssueState,
  type CreateIssueDefaults,
} from './create-issue-store.ts'
import { useCreateIssue } from './mutations.ts'
import { uploadFile } from './useUpload.ts'
import styles from './CreateIssueDialog.module.css'

const TYPES: { value: IssueTypeValue; label: string; icon: 'board' | 'flag' | 'check' | 'bolt' }[] = [
  { value: 'story', label: 'Story', icon: 'board' },
  { value: 'task', label: 'Task', icon: 'check' },
  { value: 'bug', label: 'Bug', icon: 'flag' },
  { value: 'epic', label: 'Epic', icon: 'bolt' },
]
const PRIORITIES: IssuePriority[] = ['highest', 'high', 'medium', 'low', 'lowest']

/**
 * The one Create Issue dialog (00-product §3.8), mounted once in the app shell. Opened by the top nav's Create
 * button and by the `c` shortcut from any screen; both go through create-issue-store.
 */
export function CreateIssueHost() {
  const { open, defaults } = useCreateIssueState()
  // the project the user is looking at, if any: the dialog's default project
  const { projectKey } = useParams({ strict: false })
  useKeyboardShortcut('c', () => openCreateIssue({ projectKey }))

  return (
    <Dialog open={open} onClose={closeCreateIssue} title="Create issue" size="lg">
      <CreateIssueForm defaults={{ projectKey, ...defaults }} />
    </Dialog>
  )
}

interface FormState {
  errors: Record<string, string>
  message?: string
}

function CreateIssueForm({ defaults }: { defaults: CreateIssueDefaults }) {
  const { data: me } = useSuspenseQuery(meQuery())
  const { data: projects = [] } = useQuery(projectsQuery())
  const create = useCreateIssue()
  const toast = useToast()
  const navigate = useNavigate()
  const summary = useRef<HTMLInputElement>(null)

  // BLOCK 1: controlled — what other fields depend on (project → people, sprints, epics), and what "Create
  // another" must remember. Everything else is plain FormData.
  // the user's choice, else where they are, else their first project (the list may still be loading)
  const [chosenProject, setChosenProject] = useState<string | null>(null)
  const projectKey = chosenProject ?? defaults.projectKey ?? projects[0]?.key ?? ''
  const [type, setType] = useState<IssueTypeValue>('story')
  const [assigneeId, setAssigneeId] = useState('')
  const [another, setAnother] = useState(false)
  // bumped after each issue made with "Create another": the <form> remounts (its key), so every uncontrolled
  // field starts empty again while the controlled ones above keep their state
  const [round, setRound] = useState(0)

  const { data: project } = useQuery({ ...projectQuery(projectKey), enabled: projectKey !== '' })
  const { data: backlog } = useQuery({
    ...backlogQuery(projectKey),
    enabled: projectKey !== '',
    staleTime: 60_000,
  })
  const assignable = (project?.members ?? []).filter((member) => member.role !== 'viewer')
  const sprints = backlog
    ? [
        ...(backlog.activeSprint ? [backlog.activeSprint.sprint] : []),
        ...backlog.plannedSprints.map((list) => list.sprint),
      ]
    : []

  // BLOCK 2: the action — check, create, then either close or start over for the next one
  async function submit(_previous: FormState, form: FormData): Promise<FormState> {
    // every field as text ('' when empty); type, project and assignee are state
    const values = Object.fromEntries(
      ['summary', 'description', 'priority', 'parentKey', 'sprintId', 'storyPoints', 'dueDate', 'labels'].map(
        (name) => [name, text(form, name)],
      ),
    )
    const title = values.summary?.trim() ?? ''
    if (!projectKey) {
      return { errors: { projectKey: 'Choose a project.' } }
    }
    if (!title) {
      return { errors: { summary: 'Enter a summary.' } }
    }
    const points = values.storyPoints?.trim() ?? ''
    const labels = (values.labels ?? '').split(/[\s,]+/).filter(Boolean)
    const files = form.getAll('files').filter((file): file is File => file instanceof File && file.size > 0)
    try {
      const issue = await create.mutateAsync({
        projectKey,
        type,
        summary: title,
        description: orNull(values.description),
        assigneeId: orNull(assigneeId),
        priority: (orNull(values.priority) ?? 'medium') as IssuePriority,
        sprintId: orNull(values.sprintId),
        parentKey: type === 'epic' ? null : orNull(values.parentKey),
        storyPoints: points === '' ? null : Number(points),
        dueDate: orNull(values.dueDate),
        labels,
      })
      toast(`${issue.key} created.`, 'success', {
        label: 'View',
        onClick: () => {
          void navigate({
            to: '/p/$projectKey/issues/$issueKey',
            params: { projectKey, issueKey: issue.key },
          })
        },
      })
      if (files.length > 0) {
        void uploadAll(issue.key, files).then((failed) => {
          if (failed > 0)
            toast(
              `${String(failed)} of ${String(files.length)} files couldn't be attached to ${issue.key}.`,
              'error',
            )
        })
      }
      if (another) {
        // project, type and the switch stay; a fresh form for the next one
        setAssigneeId('')
        setRound((current) => current + 1)
      } else {
        closeCreateIssue()
      }
      return { errors: {} }
    } catch (error) {
      return { errors: isApiError(error) ? error.fieldErrors : {}, message: messageOf(error) }
    }
  }

  const [state, dispatch, pending] = useActionState(submit, { errors: {} })

  // the summary is what people type first, on opening and after each "Create another". After a frame:
  // showModal() (the Dialog's own effect, which runs after ours) focuses the first control, and we want to win
  useEffect(() => {
    const frame = requestAnimationFrame(() => summary.current?.focus())
    return () => {
      cancelAnimationFrame(frame)
    }
  }, [round])

  // BLOCK 3: submitting. Not <form action={…}>: after an action, React resets the form to its default
  // values — and a reset also unchecks controlled radios and checkboxes (the type, the switch) without React
  // noticing, since their state didn't change. So the submit handler dispatches the same action itself:
  // nothing is reset on a failure (what was typed stays), and a success remounts the form instead.
  return (
    <form
      key={round}
      className={styles.form}
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        const data = new FormData(event.currentTarget)
        startTransition(() => {
          dispatch(data)
        })
      }}
    >
      <FormAlert message={state.message} />

      {/* BLOCK 3: what and where */}
      <div className={styles.row}>
        <Field label="Project" error={state.errors.projectKey}>
          {(control) => (
            <Select
              {...control}
              value={projectKey}
              onChange={(event) => {
                setChosenProject(event.target.value)
                setAssigneeId('')
              }}
            >
              {/* until the list has loaded, the project we're in is the one choice */}
              {projectKey !== '' && !projects.some((p) => p.key === projectKey) && (
                <option value={projectKey}>{projectKey}</option>
              )}
              {projects.map((p) => (
                <option key={p.key} value={p.key}>
                  {p.name} ({p.key})
                </option>
              ))}
            </Select>
          )}
        </Field>
        <div className={styles.typeField}>
          <span className={styles.label} aria-hidden="true">
            Issue type
          </span>
          <Segmented label="Issue type" value={type} options={TYPES} onChange={setType} />
        </div>
      </div>

      <Field label="Summary" error={state.errors.summary}>
        {(control) => <TextInput {...control} ref={summary} name="summary" maxLength={255} />}
      </Field>
      <Field
        label="Description"
        hint="Plain text; blank lines start new paragraphs."
        error={state.errors.description}
      >
        {(control) => <Textarea {...control} name="description" rows={3} maxLength={50_000} />}
      </Field>

      {/* BLOCK 4: people and planning */}
      <div className={styles.row}>
        <Field label="Assignee" error={state.errors.assigneeId}>
          {(control) => (
            <div className={styles.assignee}>
              <Select {...control} value={assigneeId} onChange={(event) => setAssigneeId(event.target.value)}>
                <option value="">Unassigned</option>
                {assignable.map((member) => (
                  <option key={member.userId} value={member.userId}>
                    {member.displayName}
                  </option>
                ))}
              </Select>
              {assignable.some((member) => member.userId === me.id) && assigneeId !== me.id && (
                <Button size="sm" variant="ghost" onClick={() => setAssigneeId(me.id)}>
                  Assign to me
                </Button>
              )}
            </div>
          )}
        </Field>
        <Field label="Priority" error={state.errors.priority}>
          {(control) => (
            <Select {...control} name="priority" defaultValue="medium">
              {PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>
                  {priority.charAt(0).toUpperCase() + priority.slice(1)}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      <div className={styles.row}>
        {type !== 'epic' && (
          <Field label="Parent epic" error={state.errors.parentKey}>
            {(control) => (
              <Select {...control} name="parentKey" defaultValue={defaults.parentKey ?? ''}>
                <option value="">None</option>
                {(backlog?.epics ?? []).map((epic) => (
                  <option key={epic.id} value={epic.key}>
                    {epic.key} · {epic.summary}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        )}
        <Field label="Sprint" error={state.errors.sprintId}>
          {(control) => (
            <Select {...control} name="sprintId" defaultValue={defaults.sprintId ?? ''}>
              <option value="">Backlog</option>
              {sprints.map((sprint) => (
                <option key={sprint.id} value={sprint.id}>
                  {sprint.name}
                  {sprint.state === 'active' ? ' (active)' : ''}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      <div className={styles.row}>
        <Field label="Story points" error={state.errors.storyPoints}>
          {(control) => (
            <TextInput
              {...control}
              name="storyPoints"
              type="number"
              min={0}
              max={100}
              step={0.5}
              inputMode="decimal"
            />
          )}
        </Field>
        <Field label="Due date" error={state.errors.dueDate}>
          {(control) => <TextInput {...control} name="dueDate" type="date" />}
        </Field>
      </div>
      <Field label="Labels" hint="Separated by spaces or commas." error={state.errors.labels}>
        {(control) => <TextInput {...control} name="labels" />}
      </Field>
      <Field label="Attachments" hint="Up to 25 MB each; uploaded once the issue exists.">
        {(control) => <input {...control} name="files" type="file" multiple className={styles.files} />}
      </Field>

      {/* BLOCK 5: create another + buttons */}
      <FormActions>
        <label className={styles.another}>
          <input
            type="checkbox"
            role="switch"
            checked={another}
            onChange={(event) => setAnother(event.target.checked)}
          />
          Create another
        </label>
        <Button variant="ghost" onClick={closeCreateIssue}>
          Cancel
        </Button>
        <Button type="submit" variant="primary" disabled={pending}>
          {pending ? 'Creating…' : 'Create'}
        </Button>
      </FormActions>
    </form>
  )
}

/** An empty form field means "not set": null on the wire. */
function orNull(value: string | undefined): string | null {
  const trimmed = value?.trim() ?? ''
  return trimmed === '' ? null : trimmed
}

/** Attach files to a freshly created issue; resolves with how many failed. */
async function uploadAll(issueKey: string, files: File[]): Promise<number> {
  const results = await Promise.allSettled(files.map((file) => uploadFile(issueKey, file)))
  return results.filter((result) => result.status === 'rejected').length
}

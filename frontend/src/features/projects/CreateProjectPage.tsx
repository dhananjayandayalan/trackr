import { useQuery, useSuspenseQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useActionState, useDeferredValue, useState } from 'react'
import { useFormStatus } from 'react-dom'
import { meQuery } from '@/features/auth/queries.ts'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import type { Access, Template } from '@/shared/api/types.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { Button } from '@/shared/ui/Button.tsx'
import { Field } from '@/shared/ui/Field.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { Textarea, TextInput } from '@/shared/ui/TextInput.tsx'
import { showToast } from '@/shared/ui/Toast.tsx'
import { ColorSwatches, type ProjectColor } from './ColorSwatches.tsx'
import { useCreateProject } from './mutations.ts'
import { keySuggestionQuery } from './queries.ts'
import { TemplatePicker } from './TemplatePicker.tsx'
import styles from './CreateProjectPage.module.css'

interface FormState {
  errors: Record<string, string>
  message?: string
  values: { access?: string; description?: string; invite?: string }
}

const initial: FormState = { errors: {}, values: {} }

/** Create project (00-product §3.4): template, name, key (suggested from the name), access, colour, invites. */
export function CreateProjectPage() {
  const { data: me } = useSuspenseQuery(meQuery())
  const create = useCreateProject()
  const navigate = useNavigate()

  // BLOCK 1: controlled fields — the ones other fields react to (name → key) or that React must not reset
  // after a failed submit (an action's form is reset when the action returns; controlled values stay)
  const [template, setTemplate] = useState<Template>('scrum')
  const [color, setColor] = useState<ProjectColor>('violet')
  const [name, setName] = useState('')
  const [typedKey, setTypedKey] = useState<string | null>(null)

  // BLOCK 2: the key suggestion. useDeferredValue is not a debounce: there is no timer. React renders the
  // input with every keystroke first (urgent) and the deferred value as soon as it is idle, so a fast typist's
  // in-between names are often skipped. Each name is its own query key (cached), and below two letters
  // nothing is asked (enabled: false).
  const deferredName = useDeferredValue(name.trim())
  const suggestion = useQuery({ ...keySuggestionQuery(deferredName), enabled: deferredName.length > 1 })
  const key = typedKey ?? suggestion.data?.key ?? ''

  // BLOCK 3: the action — FormData in, field errors out
  async function submit(_previous: FormState, form: FormData): Promise<FormState> {
    const values = {
      access: text(form, 'access'),
      description: text(form, 'description'),
      invite: text(form, 'invite'),
    }
    try {
      const project = await create.mutateAsync({
        name: name.trim(),
        key,
        template,
        access: values.access as Access,
        color,
        description: values.description.trim() || null,
        invite: values.invite.split(/[\s,;]+/).filter(Boolean),
      })
      showToast(`Created ${project.name} (${project.key}).`, 'success')
      // not awaited: the action ends with the request (see LoginPage)
      void navigate({ to: '/p/$projectKey', params: { projectKey: project.key } })
      return initial
    } catch (error) {
      if (isApiError(error, 'PROJECT_KEY_TAKEN')) {
        return { errors: { key: `${key} is already used in this workspace. Pick another key.` }, values }
      }
      return { errors: isApiError(error) ? error.fieldErrors : {}, message: messageOf(error), values }
    }
  }

  const [state, formAction] = useActionState(submit, initial)

  return (
    <div className={styles.root}>
      <header className={styles.header}>
        <p className={styles.crumbs}>
          <Link to="/projects">Projects</Link> / New
        </p>
        <h1>Create a project</h1>
      </header>

      <form action={formAction} className={styles.form}>
        {state.message && (
          <p role="alert" className={styles.alert}>
            {state.message}
          </p>
        )}
        <TemplatePicker value={template} onChange={setTemplate} />

        <div className={styles.row}>
          <Field label="Name" error={state.errors.name}>
            {(control) => (
              <TextInput
                {...control}
                name="name"
                required
                maxLength={80}
                autoComplete="off"
                value={name}
                onChange={(event) => {
                  setName(event.target.value)
                }}
              />
            )}
          </Field>
          <Field
            label="Key"
            error={state.errors.key}
            hint={
              suggestion.data && !suggestion.data.available && typedKey === null
                ? 'Every suggestion is taken; type your own.'
                : 'Issue keys start with it: MAR-1, MAR-2… 2 to 6 capital letters.'
            }
          >
            {(control) => (
              <TextInput
                {...control}
                name="key"
                required
                pattern="[A-Z]{2,6}"
                maxLength={6}
                autoComplete="off"
                className={styles.key}
                value={key}
                onChange={(event) => {
                  setTypedKey(event.target.value.toUpperCase().replace(/[^A-Z]/g, ''))
                }}
              />
            )}
          </Field>
        </div>

        <div className={styles.row}>
          <Field label="Access" error={state.errors.access}>
            {(control) => (
              <Select {...control} name="access" defaultValue={state.values.access ?? 'limited'}>
                <option value="private">Private — members only</option>
                <option value="limited">Limited — the workspace can view</option>
                <option value="open">Open — the workspace can contribute</option>
              </Select>
            )}
          </Field>
          <div className={styles.lead}>
            <span className={styles.leadLabel}>Lead</span>
            <span className={styles.leadValue}>
              <Avatar
                name={me.displayName}
                initials={me.initials}
                color={avatarColor(me.avatarColor)}
                size="sm"
              />
              {me.displayName} (you)
            </span>
          </div>
        </div>

        <ColorSwatches value={color} onChange={setColor} />

        <Field label="Description" error={state.errors.description}>
          {(control) => (
            <Textarea
              {...control}
              name="description"
              maxLength={2000}
              defaultValue={state.values.description ?? ''}
            />
          )}
        </Field>
        <Field
          label="Invite people"
          hint="Email addresses, separated by commas or new lines."
          error={state.errors.invite}
        >
          {(control) => <Textarea {...control} name="invite" defaultValue={state.values.invite ?? ''} />}
        </Field>

        <div className={styles.actions}>
          <CreateButton />
          <Link to="/projects">Cancel</Link>
        </div>
      </form>
    </div>
  )
}

function CreateButton() {
  const { pending } = useFormStatus()
  return (
    <Button type="submit" variant="primary" size="lg" disabled={pending}>
      {pending ? 'Creating…' : 'Create project'}
    </Button>
  )
}

function text(form: FormData, name: string): string {
  const value = form.get(name)
  return typeof value === 'string' ? value : ''
}

import { useSuspenseQuery } from '@tanstack/react-query'
import { getRouteApi } from '@tanstack/react-router'
import { useState, type SubmitEvent } from 'react'
import { ColorSwatches, projectColors, type ProjectColor } from '@/features/projects/ColorSwatches.tsx'
import { projectQuery } from '@/features/projects/queries.ts'
import type { Access, ProjectDetails } from '@/shared/api/types.ts'
import { text } from '@/shared/lib/forms.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Field } from '@/shared/ui/Field.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { Tabs } from '@/shared/ui/Tabs.tsx'
import { Textarea, TextInput } from '@/shared/ui/TextInput.tsx'
import { TypeIcon } from '@/shared/ui/TypeIcon.tsx'
import { useUpdateProject } from './mutations.ts'
import { PeopleSettings } from './PeopleSettings.tsx'
import { WorkflowEditor } from './WorkflowEditor.tsx'
import styles from './Settings.module.css'

const route = getRouteApi('/_authed/p/$projectKey/settings/$section')

export const sections = [
  'details',
  'people',
  'workflow',
  'issue-types',
  'notifications',
  'permissions',
] as const
export type Section = (typeof sections)[number]

const labels: Record<Section, string> = {
  details: 'Details',
  people: 'People',
  workflow: 'Workflow',
  'issue-types': 'Issue types',
  notifications: 'Notifications',
  permissions: 'Permissions',
}

/**
 * Project settings (00-product §3.12): one tab per section, the section in the URL (/settings/workflow), so a
 * link can point straight at the workflow editor. Only admins can change anything; everyone else reads.
 */
export function SettingsPage() {
  const { projectKey, section } = route.useParams()
  const navigate = route.useNavigate()
  const { data: project } = useSuspenseQuery(projectQuery(projectKey))
  const current: Section = (sections as readonly string[]).includes(section)
    ? (section as Section)
    : 'details'
  const canEdit = project.myRole === 'admin'

  return (
    <div className={styles.page}>
      <header>
        <p className={styles.eyebrow}>{project.name}</p>
        <h1>Project settings</h1>
        {!canEdit && <p className={styles.muted}>Only project admins can change these settings.</p>}
      </header>
      <Tabs
        label="Settings sections"
        value={current}
        tabs={sections.map((value) => ({ value, label: labels[value] }))}
        onChange={(next) => void navigate({ params: { projectKey, section: next } })}
      >
        {current === 'details' && (
          <DetailsSettings key={project.version} project={project} canEdit={canEdit} />
        )}
        {current === 'people' && <PeopleSettings projectKey={projectKey} canEdit={canEdit} />}
        {current === 'workflow' && <WorkflowEditor projectKey={projectKey} canEdit={canEdit} />}
        {current === 'issue-types' && <IssueTypes />}
        {current === 'notifications' && <NotificationSettings />}
        {current === 'permissions' && <Permissions />}
      </Tabs>
    </div>
  )
}

// BLOCK 1: details — a form over the project's own fields, saved as one merge patch with If-Match
function DetailsSettings({ project, canEdit }: { project: ProjectDetails; canEdit: boolean }) {
  const update = useUpdateProject(project.key)
  const [color, setColor] = useState<ProjectColor>(
    (projectColors as readonly string[]).includes(project.color) ? (project.color as ProjectColor) : 'pink',
  )
  return (
    <form
      className={styles.details}
      onSubmit={(event: SubmitEvent<HTMLFormElement>) => {
        event.preventDefault()
        const form = new FormData(event.currentTarget)
        update.mutate({
          version: project.version,
          patch: {
            name: text(form, 'name').trim(),
            description: text(form, 'description').trim() || null,
            leadId: text(form, 'leadId') || project.leadId,
            access: (text(form, 'access') || project.access) as Access,
            color,
          },
        })
      }}
    >
      <fieldset disabled={!canEdit} className={styles.fieldset}>
        <legend className="visually-hidden">Project details</legend>
        <Field label="Name">
          {(control) => (
            <TextInput {...control} name="name" defaultValue={project.name} required maxLength={80} />
          )}
        </Field>
        <Field label="Key" hint="The key is part of every issue key; it can't change.">
          {(control) => <TextInput {...control} value={project.key} readOnly />}
        </Field>
        <Field label="Description">
          {(control) => (
            <Textarea {...control} name="description" defaultValue={project.description ?? ''} rows={3} />
          )}
        </Field>
        <Field label="Lead">
          {(control) => (
            <Select {...control} name="leadId" defaultValue={project.leadId}>
              {project.members.map((member) => (
                <option key={member.userId} value={member.userId}>
                  {member.displayName}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Access">
          {(control) => (
            <Select {...control} name="access" defaultValue={project.access}>
              <option value="private">Private — members only</option>
              <option value="limited">Limited — the workspace can view</option>
              <option value="open">Open — the workspace can edit</option>
            </Select>
          )}
        </Field>
        <ColorSwatches value={color} onChange={setColor} />
        {canEdit && (
          <div>
            <Button type="submit" variant="primary" disabled={update.isPending}>
              Save details
            </Button>
          </div>
        )}
      </fieldset>
    </form>
  )
}

// BLOCK 2: read-only sections — what v1 has, said plainly (00-product §7: no custom issue types, no permission schemes)
function IssueTypes() {
  const types = [
    { type: 'story', text: 'A user-facing piece of work.' },
    { type: 'bug', text: 'Something broken.' },
    { type: 'task', text: 'Work that is neither a story nor a bug.' },
    {
      type: 'epic',
      text: 'A large body of work; stories, bugs and tasks belong to it. Shown on the timeline.',
    },
    { type: 'subtask', text: 'A step of another issue, in the same project.' },
  ] as const
  return (
    <ul role="list" className={styles.typeList}>
      {types.map((item) => (
        <li key={item.type}>
          <TypeIcon type={item.type} />
          <strong>{item.type.charAt(0).toUpperCase() + item.type.slice(1)}</strong>
          <span className={styles.muted}>{item.text}</span>
        </li>
      ))}
      <li className={styles.muted}>Every project has all five types; custom types are not part of v1.</li>
    </ul>
  )
}

function NotificationSettings() {
  const triggers = [
    'An issue is assigned to you',
    'You are @mentioned in a comment',
    'Someone comments on an issue you watch',
    'An issue you watch changes status',
  ]
  return (
    <div className={styles.block}>
      <p>People get these in the bell, and by email as a digest every 10 minutes:</p>
      <ul role="list" className={styles.triggers}>
        {triggers.map((trigger) => (
          <li key={trigger}>
            <input type="checkbox" checked readOnly disabled aria-label={trigger} /> {trigger}
          </li>
        ))}
      </ul>
      <p className={styles.muted}>
        Per-person email preferences arrive with user settings; in v1 every trigger is on.
      </p>
    </div>
  )
}

function Permissions() {
  const rows: [string, boolean, boolean, boolean][] = [
    ['View issues, board, reports', true, true, true],
    ['Comment', false, true, true],
    ['Create, edit, move issues', false, true, true],
    ['Manage sprints', false, true, true],
    ['Edit project details and workflow', false, false, true],
    ['Manage people', false, false, true],
  ]
  return (
    <table className={styles.permissions}>
      <caption className="visually-hidden">What each project role may do</caption>
      <thead>
        <tr>
          <th scope="col">Permission</th>
          <th scope="col">Viewer</th>
          <th scope="col">Member</th>
          <th scope="col">Admin</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(([name, ...allowed]) => (
          <tr key={name}>
            <th scope="row">{name}</th>
            {allowed.map((yes, i) => (
              <td key={['viewer', 'member', 'admin'][i]}>
                {yes ? <span aria-label="allowed">✓</span> : <span aria-label="not allowed">—</span>}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

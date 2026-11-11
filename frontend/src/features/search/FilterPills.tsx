import { useQueries, useSuspenseQuery } from '@tanstack/react-query'
import { useId } from 'react'
import { projectsQuery } from '@/features/projects/queries.ts'
import { membersQuery, workflowQuery } from '@/features/settings/queries.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Menu, MenuCheckbox } from '@/shared/ui/Menu.tsx'
import { CURRENT_USER, UNASSIGNED, type Pills } from './tql.ts'
import styles from './FilterPills.module.css'

interface Option {
  value: string
  label: string
}

const capitalised = (value: string) => value.charAt(0).toUpperCase() + value.slice(1)
const typeOptions: Option[] = ['story', 'bug', 'task', 'epic', 'subtask'].map((value) => ({
  value,
  label: capitalised(value),
}))
const priorityOptions: Option[] = ['highest', 'high', 'medium', 'low', 'lowest'].map((value) => ({
  value,
  label: capitalised(value),
}))

/**
 * Basic mode (00-product §3.11): one pill per field, each a menu of checkboxes. The pills are only state; the
 * query is pillsToTql(pills), computed by the page. Statuses and people come from the chosen projects (or every
 * visible project when none is chosen), from each project's workflow and member list.
 */
export function FilterPills({ pills, onChange }: { pills: Pills; onChange: (pills: Pills) => void }) {
  const textId = useId()
  const { data: projects } = useSuspenseQuery(projectsQuery())
  const scope = pills.projects.length > 0 ? pills.projects : projects.map((project) => project.key)
  const workflows = useQueries({ queries: scope.map((key) => workflowQuery(key)) })
  const members = useQueries({ queries: scope.map((key) => membersQuery(key)) })

  // BLOCK 1: options from the projects in scope, deduplicated by name (several projects share "Done")
  const statusNames = [
    ...new Set(
      workflows.flatMap((result) => result.data?.workflow.statuses.map((status) => status.name) ?? []),
    ),
  ]
  const people = new Set(members.flatMap((result) => result.data?.map((member) => member.displayName) ?? []))

  const toggle = (field: keyof Omit<Pills, 'text'>, value: string, checked: boolean) => {
    const current = pills[field]
    onChange({ ...pills, [field]: checked ? [...current, value] : current.filter((v) => v !== value) })
  }

  const pill = (field: keyof Omit<Pills, 'text'>, label: string, options: Option[]) => {
    const chosen = pills[field]
    const summary =
      chosen.length === 0
        ? label
        : `${label}: ${options.find((o) => o.value === chosen[0])?.label ?? chosen[0] ?? ''}${
            chosen.length > 1 ? ` +${String(chosen.length - 1)}` : ''
          }`
    return (
      <Menu
        label={`${label} filter`}
        icon="chevron-down"
        text={summary}
        variant={chosen.length > 0 ? 'primary' : 'secondary'}
      >
        {options.length === 0 && <p className={styles.none}>Nothing to choose from</p>}
        {options.map((option) => (
          <MenuCheckbox
            key={option.value}
            checked={chosen.includes(option.value)}
            onChange={(checked) => {
              toggle(field, option.value, checked)
            }}
          >
            {option.label}
          </MenuCheckbox>
        ))}
      </Menu>
    )
  }

  const any =
    pills.text !== '' ||
    (['projects', 'types', 'statuses', 'assignees', 'priorities'] as const).some((f) => pills[f].length > 0)

  return (
    <div className={styles.root} role="group" aria-label="Filters">
      {/* BLOCK 2: free text first — the most common filter */}
      <div className={styles.search}>
        <label htmlFor={textId} className="visually-hidden">
          Search text
        </label>
        <Icon name="search" size="sm" className={styles.searchIcon} />
        <input
          id={textId}
          type="search"
          className={styles.searchInput}
          placeholder="Search issues"
          value={pills.text}
          onChange={(event) => {
            onChange({ ...pills, text: event.target.value })
          }}
        />
      </div>
      {pill(
        'projects',
        'Project',
        projects.map((project) => ({ value: project.key, label: `${project.key} · ${project.name}` })),
      )}
      {pill('types', 'Type', typeOptions)}
      {pill(
        'statuses',
        'Status',
        statusNames.map((name) => ({ value: name, label: name })),
      )}
      {pill('assignees', 'Assignee', [
        { value: CURRENT_USER, label: 'Me' },
        { value: UNASSIGNED, label: 'Unassigned' },
        ...[...people].sort().map((name) => ({ value: name, label: name })),
      ])}
      {pill('priorities', 'Priority', priorityOptions)}
      {any && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            onChange({ projects: [], types: [], statuses: [], assignees: [], priorities: [], text: '' })
          }}
        >
          Clear
        </Button>
      )}
    </div>
  )
}

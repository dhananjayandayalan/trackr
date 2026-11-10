import { useSuspenseQuery } from '@tanstack/react-query'
import { getRouteApi } from '@tanstack/react-router'
import { useId, useState, type ReactNode } from 'react'
import type { BacklogSearch } from '@/app/router.tsx'
import { LiveIndicator } from '@/features/board/LiveIndicator.tsx'
import { IssuePanel } from '@/features/issue/IssuePanel.tsx'
import { projectQuery } from '@/features/projects/queries.ts'
import type { SprintView } from '@/shared/api/types.ts'
import { dayLabel } from '@/shared/lib/dates.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Dialog } from '@/shared/ui/Dialog.tsx'
import { FormActions } from '@/shared/ui/Form.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Menu, MenuItem } from '@/shared/ui/Menu.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { useToast } from '@/shared/ui/Toast.tsx'
import { findRow, rowsOf, type ListId, type LiveRow, type LiveSprintList } from './backlog-cache.ts'
import { CompleteSprintDialog } from './CompleteSprintDialog.tsx'
import { EpicsPanel } from './EpicsPanel.tsx'
import { useCreateSprint, useDeleteSprint, useMoveBacklogIssue } from './mutations.ts'
import { backlogQuery } from './queries.ts'
import { SprintSection } from './SprintSection.tsx'
import { StartSprintDialog } from './StartSprintDialog.tsx'
import { resolveBacklogDrop, useBacklogMonitor } from './useBacklogDnd.ts'
import styles from './BacklogPage.module.css'

const route = getRouteApi('/_authed/p/$projectKey/backlog')

/** /p/$projectKey/backlog (00-product §3.6): the active sprint, the planned ones, and the backlog. */
export function BacklogPage() {
  // BLOCK 1: data, cached by the loaders
  const { projectKey } = route.useParams()
  const search = route.useSearch()
  const navigate = route.useNavigate()
  const { data: project } = useSuspenseQuery(projectQuery(projectKey))
  const { data: backlog } = useSuspenseQuery(backlogQuery(projectKey))
  const move = useMoveBacklogIssue(projectKey)
  const createSprint = useCreateSprint(projectKey)
  const toast = useToast()
  const canEdit = project.myRole !== 'viewer'
  const searchId = useId()
  const typeId = useId()

  // BLOCK 2: filters (search params), applied in the browser
  const q = search.q?.trim().toLowerCase() ?? ''
  const visible = (row: LiveRow) =>
    (search.type === undefined || row.type === search.type) &&
    (search.epic === undefined || row.epicId === search.epic) &&
    (q === '' || row.summary.toLowerCase().includes(q) || row.key.toLowerCase() === q)
  const set = (changes: Partial<BacklogSearch>, replace = false) => {
    void navigate({ search: (prev) => ({ ...prev, ...changes }), replace })
  }

  const epics = new Map(backlog.epics.map((epic) => [epic.id, epic]))
  const members = new Map(project.members.map((member) => [member.userId, member]))

  // BLOCK 3: a drop → a move (same scope as the board's moves)
  useBacklogMonitor((source, target) => {
    if (!target) {
      return
    }
    const rows = (rowsOf(backlog, target.listId) ?? []).filter(visible)
    const next = resolveBacklogDrop(source, target, rows)
    if (next) {
      const version = findRow(backlog, source.key)?.row.version ?? source.version
      move.mutate({ ...next, version })
    }
  })

  const section = (
    list: LiveSprintList | { sprint?: undefined; issues: LiveRow[] },
    listId: ListId,
    extra: {
      title: string
      meta?: ReactNode
      actions?: ReactNode
    },
  ) => (
    <SprintSection
      key={listId ?? 'backlog'}
      projectKey={projectKey}
      listId={listId}
      allRows={list.issues}
      rows={list.issues.filter(visible)}
      epics={epics}
      members={members}
      canEdit={canEdit}
      {...extra}
    />
  )

  return (
    <div className={styles.page}>
      {/* BLOCK 4: header + toolbar */}
      <header className={styles.header}>
        <div>
          <p className={styles.eyebrow}>{project.name}</p>
          <h1>Backlog</h1>
        </div>
        <div className={styles.headerActions}>
          <LiveIndicator people={project.members.map((member) => ({ ...member, id: member.userId }))} />
          {canEdit && (
            <Button
              icon="plus"
              disabled={createSprint.isPending}
              onClick={() => {
                createSprint.mutate(undefined, {
                  onSuccess: (sprint) => {
                    toast(`${sprint.name} created.`, 'success')
                  },
                })
              }}
            >
              Create sprint
            </Button>
          )}
        </div>
      </header>

      <div className={styles.toolbar} role="toolbar" aria-label="Backlog filters">
        <label htmlFor={searchId} className="visually-hidden">
          Filter issues
        </label>
        <span className={styles.search}>
          <Icon name="search" size="sm" className={styles.searchIcon} />
          <TextInput
            id={searchId}
            type="search"
            placeholder="Filter issues"
            defaultValue={search.q ?? ''}
            onChange={(event) => {
              set({ q: event.target.value || undefined }, true)
            }}
          />
        </span>
        <label htmlFor={typeId} className="visually-hidden">
          Issue type
        </label>
        <Select
          id={typeId}
          value={search.type ?? ''}
          onChange={(event) => {
            const type = event.target.value as BacklogSearch['type'] | ''
            set({ type: type === '' ? undefined : type })
          }}
        >
          <option value="">All types</option>
          <option value="story">Stories</option>
          <option value="task">Tasks</option>
          <option value="bug">Bugs</option>
        </Select>
      </div>

      {/* BLOCK 5: epics beside the lists */}
      <div className={styles.layout}>
        <EpicsPanel
          epics={backlog.epics}
          selected={search.epic}
          onSelect={(epic) => {
            set({ epic })
          }}
        />
        <div className={styles.lists}>
          {backlog.activeSprint &&
            section(backlog.activeSprint, backlog.activeSprint.sprint.id, {
              title: backlog.activeSprint.sprint.name,
              meta: <SprintMeta sprint={backlog.activeSprint.sprint} />,
              actions: canEdit && (
                <CompleteButton projectKey={projectKey} sprint={backlog.activeSprint.sprint} />
              ),
            })}
          {backlog.plannedSprints.map((list) =>
            section(list, list.sprint.id, {
              title: list.sprint.name,
              meta: <SprintMeta sprint={list.sprint} />,
              actions: canEdit && (
                <PlannedActions projectKey={projectKey} list={list} canStart={!backlog.activeSprint} />
              ),
            }),
          )}
          {section(backlog.backlog, null, { title: 'Backlog' })}
        </div>
      </div>
      {search.issue && (
        <IssuePanel
          issueKey={search.issue}
          onClose={() => {
            void navigate({ search: (prev) => ({ ...prev, issue: undefined }), viewTransition: true })
          }}
        />
      )}
    </div>
  )
}

function SprintMeta({ sprint }: { sprint: SprintView }) {
  if (sprint.startsOn && sprint.endsOn) {
    return (
      <>
        {dayLabel(sprint.startsOn)} – {dayLabel(sprint.endsOn)}
        {sprint.goal ? ` · ${sprint.goal}` : ''}
      </>
    )
  }
  return sprint.goal ? <>{sprint.goal}</> : null
}

function CompleteButton({ projectKey, sprint }: { projectKey: string; sprint: SprintView }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button
        size="sm"
        variant="primary"
        onClick={() => {
          setOpen(true)
        }}
      >
        Complete sprint
      </Button>
      <CompleteSprintDialog
        open={open}
        onClose={() => {
          setOpen(false)
        }}
        projectKey={projectKey}
        sprint={sprint}
      />
    </>
  )
}

// BLOCK 6: a planned sprint's actions — start (only while no sprint is active: one at a time, 00-product §4.5),
// and delete, behind a confirmation; its issues go back to the backlog
function PlannedActions({
  projectKey,
  list,
  canStart,
}: {
  projectKey: string
  list: LiveSprintList
  canStart: boolean
}) {
  const [starting, setStarting] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const remove = useDeleteSprint(projectKey)
  const toast = useToast()
  return (
    <>
      <Button
        size="sm"
        variant={canStart ? 'primary' : 'secondary'}
        disabled={!canStart}
        title={canStart ? undefined : 'Complete the active sprint first'}
        onClick={() => {
          setStarting(true)
        }}
      >
        Start sprint
      </Button>
      <Menu label={`More actions for ${list.sprint.name}`}>
        <MenuItem
          onSelect={() => {
            setDeleting(true)
          }}
        >
          Delete sprint
        </MenuItem>
      </Menu>
      <StartSprintDialog
        open={starting}
        onClose={() => {
          setStarting(false)
        }}
        projectKey={projectKey}
        sprint={list.sprint}
        issueCount={list.issues.length}
      />
      <Dialog
        open={deleting}
        onClose={() => {
          setDeleting(false)
        }}
        title={`Delete ${list.sprint.name}?`}
        description={`Its ${list.issues.length} ${list.issues.length === 1 ? 'issue goes' : 'issues go'} back to the backlog.`}
      >
        <FormActions>
          <Button
            variant="ghost"
            onClick={() => {
              setDeleting(false)
            }}
          >
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={remove.isPending}
            onClick={() => {
              remove.mutate(list.sprint.id, {
                onSuccess: () => {
                  toast(`${list.sprint.name} deleted.`, 'success')
                  setDeleting(false)
                },
                onError: (error) => {
                  toast(`Couldn't delete the sprint: ${error.message}`, 'error')
                },
              })
            }}
          >
            Delete sprint
          </Button>
        </FormActions>
      </Dialog>
    </>
  )
}

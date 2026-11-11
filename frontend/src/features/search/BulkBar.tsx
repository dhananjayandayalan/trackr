import { useQuery, useSuspenseQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { meQuery } from '@/features/auth/queries.ts'
import { backlogQuery } from '@/features/backlog/queries.ts'
import { membersQuery, workflowQuery } from '@/features/settings/queries.ts'
import type { SearchHit } from '@/shared/api/types.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Dialog } from '@/shared/ui/Dialog.tsx'
import { Menu, MenuGroup, MenuItem } from '@/shared/ui/Menu.tsx'
import { useBulk, type BulkVars } from './mutations.ts'
import styles from './BulkBar.module.css'

/**
 * What can be done to the checked rows at once (00-product §3.11): transition, assign, move to sprint, delete.
 * Statuses, people and sprints belong to a project, so those menus need every checked issue to be in one;
 * assigning to yourself or nobody works across projects.
 */
export function BulkBar({ rows, onDone }: { rows: SearchHit[]; onDone: () => void }) {
  const { data: me } = useSuspenseQuery(meQuery())
  const bulk = useBulk()
  const [confirmDelete, setConfirmDelete] = useState(false)
  const projects = [...new Set(rows.map((row) => row.projectKey))]
  const project = projects.length === 1 ? projects[0] : undefined
  const workflow = useQuery({ ...workflowQuery(project ?? ''), enabled: project !== undefined })
  const members = useQuery({ ...membersQuery(project ?? ''), enabled: project !== undefined })
  const backlog = useQuery({ ...backlogQuery(project ?? ''), enabled: project !== undefined })
  const keys = rows.map((row) => row.key)

  const run = (vars: Omit<BulkVars, 'keys'>) => {
    bulk.mutate({ keys, ...vars }, { onSuccess: onDone })
  }

  const sprints = [
    ...(backlog.data?.activeSprint ? [backlog.data.activeSprint.sprint] : []),
    ...(backlog.data?.plannedSprints.map((list) => list.sprint) ?? []),
  ]
  const oneProject = project ? '' : ' (choose issues of one project)'

  return (
    <div className={styles.root} role="toolbar" aria-label="Bulk actions">
      <strong className={styles.count}>{rows.length} selected</strong>
      <Menu label={`Transition${oneProject}`} icon="arrow-right" text="Transition" variant="secondary">
        {(workflow.data?.workflow.statuses ?? []).map((status) => (
          <MenuItem
            key={status.id}
            disabled={bulk.isPending}
            onSelect={() => {
              run({ action: 'transition', args: { statusId: status.id } })
            }}
          >
            {status.name}
          </MenuItem>
        ))}
        {!project && <p className={styles.hint}>Choose issues of one project</p>}
      </Menu>
      <Menu label="Assign" icon="user" text="Assign" variant="secondary">
        <MenuItem
          onSelect={() => {
            run({ action: 'assign', args: { assigneeId: me.id } })
          }}
        >
          Assign to me
        </MenuItem>
        <MenuItem
          onSelect={() => {
            run({ action: 'assign', args: { assigneeId: null } })
          }}
        >
          Unassign
        </MenuItem>
        {project && (members.data ?? []).length > 0 && (
          <MenuGroup label="People">
            {(members.data ?? [])
              .filter((member) => member.userId !== me.id)
              .map((member) => (
                <MenuItem
                  key={member.userId}
                  onSelect={() => {
                    run({ action: 'assign', args: { assigneeId: member.userId } })
                  }}
                >
                  {member.displayName}
                </MenuItem>
              ))}
          </MenuGroup>
        )}
      </Menu>
      <Menu label={`Move to sprint${oneProject}`} icon="backlog" text="Move to sprint" variant="secondary">
        {project && (
          <MenuItem
            onSelect={() => {
              run({ action: 'moveToSprint', args: { sprintId: null } })
            }}
          >
            Backlog
          </MenuItem>
        )}
        {sprints.map((sprint) => (
          <MenuItem
            key={sprint.id}
            onSelect={() => {
              run({ action: 'moveToSprint', args: { sprintId: sprint.id } })
            }}
          >
            {sprint.name}
          </MenuItem>
        ))}
        {!project && <p className={styles.hint}>Choose issues of one project</p>}
      </Menu>
      <Button
        variant="danger"
        icon="trash"
        size="sm"
        onClick={() => {
          setConfirmDelete(true)
        }}
      >
        Delete
      </Button>
      <Button variant="ghost" size="sm" onClick={onDone}>
        Clear selection
      </Button>

      {/* BLOCK 1: deleting is the one action that asks first (it is a soft delete, but out of sight is out of mind) */}
      <Dialog
        open={confirmDelete}
        onClose={() => {
          setConfirmDelete(false)
        }}
        title={`Delete ${String(rows.length)} issue${rows.length === 1 ? '' : 's'}?`}
        description="Deleted issues (and their subtasks) can be restored for 30 days."
      >
        <div className={styles.confirm}>
          <Button
            onClick={() => {
              setConfirmDelete(false)
            }}
          >
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={bulk.isPending}
            onClick={() => {
              run({ action: 'delete' })
              setConfirmDelete(false)
            }}
          >
            Delete
          </Button>
        </div>
      </Dialog>
    </div>
  )
}

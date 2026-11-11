import { useSuspenseQuery } from '@tanstack/react-query'
import { useId, useState, type SubmitEvent } from 'react'
import { meQuery } from '@/features/auth/queries.ts'
import { messageOf } from '@/shared/api/errors.ts'
import { text } from '@/shared/lib/forms.ts'
import type { MemberView, ProjectRole } from '@/shared/api/types.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { Button } from '@/shared/ui/Button.tsx'
import { Dialog } from '@/shared/ui/Dialog.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { useAddMember, useChangeRole, useRemoveMember } from './mutations.ts'
import { membersQuery } from './queries.ts'
import styles from './Settings.module.css'

const roles: { value: ProjectRole; label: string }[] = [
  { value: 'admin', label: 'Admin' },
  { value: 'member', label: 'Member' },
  { value: 'viewer', label: 'Viewer' },
]

/** People (00-product §3.12): every role change is its own PATCH; removing someone asks first. */
export function PeopleSettings({ projectKey, canEdit }: { projectKey: string; canEdit: boolean }) {
  const { data: members } = useSuspenseQuery(membersQuery(projectKey))
  const { data: me } = useSuspenseQuery(meQuery())
  const changeRole = useChangeRole(projectKey)
  const remove = useRemoveMember(projectKey)
  const [removing, setRemoving] = useState<MemberView | null>(null)

  return (
    <div className={styles.block}>
      {canEdit && <AddMember projectKey={projectKey} />}
      <table className={styles.people}>
        <caption className="visually-hidden">Members of {projectKey}</caption>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Role</th>
            <th scope="col">
              <span className="visually-hidden">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {members.map((member) => (
            <tr key={member.userId}>
              <th scope="row">
                <span className={styles.person}>
                  <Avatar
                    name={member.displayName}
                    initials={member.initials}
                    color={avatarColor(member.avatarColor)}
                    size="sm"
                  />
                  {member.displayName}
                  {member.userId === me.id && <span className={styles.muted}>(you)</span>}
                </span>
              </th>
              <td>
                <Select
                  aria-label={`Role of ${member.displayName}`}
                  value={member.role}
                  disabled={!canEdit}
                  onChange={(event) => {
                    changeRole.mutate({ userId: member.userId, role: event.target.value as ProjectRole })
                  }}
                >
                  {roles.map((role) => (
                    <option key={role.value} value={role.value}>
                      {role.label}
                    </option>
                  ))}
                </Select>
              </td>
              <td className={styles.end}>
                {canEdit && (
                  <Button
                    variant="ghost"
                    size="sm"
                    icon="trash"
                    aria-label={`Remove ${member.displayName}`}
                    onClick={() => {
                      setRemoving(member)
                    }}
                  />
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* BLOCK 1: confirm — a <dialog>, so the page behind is inert and Esc cancels */}
      <Dialog
        open={removing !== null}
        onClose={() => {
          setRemoving(null)
        }}
        title={`Remove ${removing?.displayName ?? ''}?`}
        description={`They lose access to ${projectKey} unless the project is open to the whole workspace.`}
      >
        <div className={styles.dialogActions}>
          <Button
            onClick={() => {
              setRemoving(null)
            }}
          >
            Cancel
          </Button>
          <Button
            variant="danger"
            onClick={() => {
              if (removing) {
                remove.mutate(removing.userId)
              }
              setRemoving(null)
            }}
          >
            Remove
          </Button>
        </div>
      </Dialog>
    </div>
  )
}

function AddMember({ projectKey }: { projectKey: string }) {
  const add = useAddMember(projectKey)
  const emailId = useId()
  const roleId = useId()
  return (
    <form
      className={styles.addMember}
      onSubmit={(event: SubmitEvent<HTMLFormElement>) => {
        event.preventDefault()
        const form = event.currentTarget
        const data = new FormData(form)
        add.mutate(
          { email: text(data, 'email'), role: (text(data, 'role') || 'member') as ProjectRole },
          {
            onSuccess: () => {
              form.reset()
            },
          },
        )
      }}
    >
      <label htmlFor={emailId} className="visually-hidden">
        Email
      </label>
      <TextInput id={emailId} name="email" type="email" required placeholder="name@company.com" />
      <label htmlFor={roleId} className="visually-hidden">
        Role
      </label>
      <Select id={roleId} name="role" defaultValue="member">
        {roles.map((role) => (
          <option key={role.value} value={role.value}>
            {role.label}
          </option>
        ))}
      </Select>
      <Button type="submit" variant="primary" icon="plus" disabled={add.isPending}>
        Add
      </Button>
      {add.error && (
        <p role="alert" className={styles.error}>
          {messageOf(add.error)}
        </p>
      )}
    </form>
  )
}

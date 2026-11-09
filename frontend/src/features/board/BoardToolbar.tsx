import { getRouteApi } from '@tanstack/react-router'
import { useId } from 'react'
import type { BoardSearch } from '@/app/router.tsx'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import type { LiveBoard } from './board-cache.ts'
import { UNASSIGNED } from './board-view.ts'
import styles from './BoardToolbar.module.css'

const route = getRouteApi('/_authed/p/$projectKey/board')

/**
 * Quick filters (00-product §3.5). They are search params: the URL is the state, so Back undoes a filter and a
 * shared link shows the same board. `replace` for typing, so each keystroke isn't a history entry.
 */
export function BoardToolbar({ board, search }: { board: LiveBoard; search: BoardSearch }) {
  const navigate = route.useNavigate()
  const searchId = useId()
  const groupId = useId()
  const set = (changes: Partial<BoardSearch>, replace = false) => {
    void navigate({ search: (prev) => ({ ...prev, ...changes }), replace })
  }

  // the people with a card on this board, for the avatar filter
  const assigned = new Set(
    board.columns.flatMap((column) => column.cards.map((card) => card.assigneeId ?? UNASSIGNED)),
  )
  const people = Object.entries(board.people).filter(([id]) => assigned.has(id))
  const chosen = search.assignee ?? []
  const toggleAssignee = (id: string) => {
    const next = chosen.includes(id) ? chosen.filter((a) => a !== id) : [...chosen, id]
    set({ assignee: next.length > 0 ? next : undefined })
  }

  return (
    <div className={styles.root} role="toolbar" aria-label="Board filters">
      <label htmlFor={searchId} className="visually-hidden">
        Filter cards
      </label>
      <span className={styles.search}>
        <Icon name="search" size="sm" className={styles.searchIcon} />
        <TextInput
          id={searchId}
          type="search"
          placeholder="Filter cards"
          defaultValue={search.q ?? ''}
          onChange={(event) => {
            set({ q: event.target.value || undefined }, true)
          }}
        />
      </span>

      {/* BLOCK 1: avatar filter — pressed avatars are the assignees shown */}
      {people.length > 0 && (
        <ul role="list" className={styles.people} aria-label="Filter by assignee">
          {people.map(([id, person]) => (
            <li key={id}>
              <button
                type="button"
                className={styles.person}
                aria-pressed={chosen.includes(id)}
                onClick={() => {
                  toggleAssignee(id)
                }}
              >
                <Avatar
                  name={person.name}
                  initials={person.initials}
                  color={avatarColor(person.color)}
                  size="sm"
                />
              </button>
            </li>
          ))}
        </ul>
      )}

      {/* BLOCK 2: quick filters — toggle buttons (aria-pressed), so their state is announced */}
      <div className={styles.toggles}>
        <Toggle
          pressed={search.mine ?? false}
          onChange={(mine) => {
            set({ mine: mine || undefined })
          }}
        >
          Only my issues
        </Toggle>
        <Toggle
          pressed={search.recent ?? false}
          onChange={(recent) => {
            set({ recent: recent || undefined })
          }}
        >
          Recently updated
        </Toggle>
        <Toggle
          pressed={search.bugs ?? false}
          onChange={(bugs) => {
            set({ bugs: bugs || undefined })
          }}
        >
          Bugs only
        </Toggle>
      </div>

      <span className={styles.group}>
        <label htmlFor={groupId}>Group by</label>
        <Select
          id={groupId}
          value={search.group ?? 'none'}
          onChange={(event) => {
            const group = event.target.value as NonNullable<BoardSearch['group']>
            set({ group: group === 'none' ? undefined : group })
          }}
        >
          <option value="none">None</option>
          <option value="assignee">Assignee</option>
          <option value="epic">Epic</option>
        </Select>
      </span>
    </div>
  )
}

function Toggle({
  pressed,
  onChange,
  children,
}: {
  pressed: boolean
  onChange: (pressed: boolean) => void
  children: string
}) {
  return (
    <button
      type="button"
      className={styles.toggle}
      aria-pressed={pressed}
      onClick={() => {
        onChange(!pressed)
      }}
    >
      {children}
    </button>
  )
}

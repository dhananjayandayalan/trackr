import { useQuery } from '@tanstack/react-query'
import { useId, useRef, type CSSProperties, type KeyboardEvent, type ToggleEvent } from 'react'
import type { IssueDetails } from '@/shared/api/types.ts'
import { statusTone } from '@/shared/lib/enums.ts'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Lozenge } from '@/shared/ui/Lozenge.tsx'
import { useTransitionIssue } from './mutations.ts'
import { transitionsQuery } from './queries.ts'
import styles from './StatusButton.module.css'

/**
 * The status lozenge as a button; its menu lists only the moves the workflow allows from here
 * (GET /issues/{key}/transitions, prefetched with the issue). Choosing one is an optimistic transition.
 *
 * BLOCK 1: the button names the popover (popoverTarget): click opens it, Esc and a click outside close it —
 * the Popover API, no state of ours. BLOCK 2: the menu sits in the top layer and anchor positioning puts it
 * under the button (position-area in the CSS), flipped above when there's no room below.
 */
export function StatusButton({ issue, canEdit }: { issue: IssueDetails; canEdit: boolean }) {
  const id = useId()
  const menuId = `${id}-status`
  const menu = useRef<HTMLDivElement>(null)
  const { data: targets = [] } = useQuery({ ...transitionsQuery(issue.key), enabled: canEdit })
  const transition = useTransitionIssue(issue.key)
  // one anchor name per button (CSS identifiers can't contain useId's colons)
  const anchor = { '--_anchor': `--status-${id.replace(/[^a-zA-Z0-9]/g, '-')}` } as CSSProperties
  const lozenge = (
    <Lozenge tone={statusTone(issue.status.color)} className={styles.lozenge}>
      {issue.status.name}
    </Lozenge>
  )

  if (!canEdit) {
    return lozenge
  }

  const items = () => [...(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])]

  // the menu pattern's keyboard: focus the first item on open, arrows move
  const onToggle = (event: ToggleEvent<HTMLDivElement>) => {
    if (event.newState === 'open') {
      items()[0]?.focus()
    }
  }
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const list = items()
    const current = list.indexOf(document.activeElement as HTMLElement)
    const next = {
      ArrowDown: (current + 1) % list.length,
      ArrowUp: (current - 1 + list.length) % list.length,
    }[event.key]
    if (next !== undefined && list.length > 0) {
      event.preventDefault()
      list[next]?.focus()
    }
  }

  return (
    <span style={anchor}>
      <button
        type="button"
        className={styles.trigger}
        popoverTarget={menuId}
        aria-haspopup="menu"
        aria-label={`Status: ${issue.status.name}. Change status`}
      >
        {lozenge}
        <Icon name="chevron-down" size="sm" />
      </button>
      <div
        ref={menu}
        id={menuId}
        popover="auto"
        role="menu"
        tabIndex={-1}
        aria-label={`Move ${issue.key} to`}
        className={styles.menu}
        onToggle={onToggle}
        onKeyDown={onKeyDown}
      >
        {targets.length === 0 && <p className={styles.empty}>No moves allowed from {issue.status.name}.</p>}
        {targets.map((target) => (
          <button
            key={target.id}
            type="button"
            role="menuitem"
            tabIndex={-1}
            className={styles.item}
            onClick={() => {
              transition.mutate(target)
              // jsdom (tests) has no Popover API
              if (typeof menu.current?.hidePopover === 'function') {
                menu.current.hidePopover()
              }
            }}
          >
            <Lozenge tone={statusTone(target.color)}>{target.name}</Lozenge>
          </button>
        ))}
      </div>
    </span>
  )
}

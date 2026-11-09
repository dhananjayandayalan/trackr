import {
  useId,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type ReactNode,
  type ToggleEvent,
} from 'react'
import { Button, type ButtonProps } from './Button.tsx'
import styles from './Menu.module.css'

interface MenuProps {
  /** the trigger's accessible name, e.g. "Actions for MAR-12" */
  label: string
  icon?: ButtonProps['icon']
  /** visible trigger text; without it the trigger is an icon button */
  text?: ReactNode
  variant?: ButtonProps['variant']
  size?: ButtonProps['size']
  className?: string
  children: ReactNode
}

/**
 * A menu button (08-css-volt §5): the Popover API puts the list in the top layer and closes it on Esc or a
 * click outside (popover="auto"), anchor positioning places it under its trigger. Our code only adds the
 * keyboard pattern of a menu: focus the first item on open, arrows move, an item click closes.
 */
export function Menu({
  label,
  icon = 'more',
  text,
  variant = 'ghost',
  size = 'sm',
  className,
  children,
}: MenuProps) {
  const id = useId()
  const popover = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  // BLOCK 1: one anchor name per menu (a board has a menu on every card). It reaches both elements through a
  // custom property, because React's style types don't know anchor-name yet; useId's characters aren't
  // allowed in a CSS identifier, so they become dashes.
  const anchor = { '--_anchor': `--menu-${id.replace(/[^a-zA-Z0-9]/g, '-')}` } as CSSProperties

  const items = () => [
    ...(popover.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') ?? []),
  ]

  // BLOCK 2: popover → React. The toggle event fires on every open and close, however it happened.
  const onToggle = (event: ToggleEvent<HTMLDivElement>) => {
    const nowOpen = event.newState === 'open'
    setOpen(nowOpen)
    if (nowOpen) {
      items()[0]?.focus()
    }
  }

  // BLOCK 3: arrows, Home and End move between items (Esc is the popover's own)
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const list = items()
    const current = list.indexOf(document.activeElement as HTMLElement)
    const next = {
      ArrowDown: (current + 1) % list.length,
      ArrowUp: (current - 1 + list.length) % list.length,
      Home: 0,
      End: list.length - 1,
    }[event.key]
    if (next !== undefined && list.length > 0) {
      event.preventDefault()
      list[next]?.focus()
    }
  }

  return (
    <span className={className} style={anchor}>
      <Button
        variant={variant}
        size={size}
        icon={icon}
        aria-label={text ? undefined : label}
        aria-haspopup="menu"
        aria-expanded={open}
        popoverTarget={`${id}-menu`}
        className={styles.trigger}
      >
        {text}
      </Button>
      <div
        ref={popover}
        id={`${id}-menu`}
        popover="auto"
        role="menu"
        aria-label={label}
        tabIndex={-1}
        className={styles.menu}
        onToggle={onToggle}
        onKeyDown={onKeyDown}
        // an item was chosen: close (Esc and outside clicks the browser handles)
        onClick={(event) => {
          const element = popover.current
          // jsdom (tests) has no Popover API
          if (
            element &&
            (event.target as HTMLElement).closest('[role="menuitem"]') &&
            typeof element.hidePopover === 'function'
          ) {
            element.hidePopover()
          }
        }}
      >
        {children}
      </div>
    </span>
  )
}

/** One choice. Rendered as a button, so Enter and Space work without our code. */
export function MenuItem({
  onSelect,
  disabled,
  children,
}: {
  onSelect: () => void
  disabled?: boolean
  children: ReactNode
}) {
  return (
    <button
      type="button"
      role="menuitem"
      tabIndex={-1}
      className={styles.item}
      disabled={disabled}
      onClick={onSelect}
    >
      {children}
    </button>
  )
}

/** A labelled group of items, e.g. "Move to". */
export function MenuGroup({ label, children }: { label: string; children: ReactNode }) {
  const id = useId()
  return (
    <div role="group" aria-labelledby={id} className={styles.group}>
      <span id={id} className={styles.groupLabel}>
        {label}
      </span>
      {children}
    </div>
  )
}

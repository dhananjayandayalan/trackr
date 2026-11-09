import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react'
import styles from './Tabs.module.css'

export interface TabItem<T extends string> {
  value: T
  label: string
  count?: number | undefined
}

interface TabsProps<T extends string> {
  label: string
  value: T
  tabs: readonly TabItem<T>[]
  onChange: (value: T) => void
  /** The selected tab's content. */
  children: ReactNode
}

/**
 * The ARIA tabs pattern: a tablist of tab buttons and one tabpanel. Roving tabindex — only the selected tab
 * is in the Tab order; ←/→/Home/End move between tabs and select them (automatic activation), so the
 * keyboard reaches the panel in one Tab press instead of passing every tab.
 */
export function Tabs<T extends string>({ label, value, tabs, onChange, children }: TabsProps<T>) {
  const base = useId()
  const buttons = useRef(new Map<T, HTMLButtonElement>())
  const tabId = (tab: T) => `${base}-tab-${tab}`

  function onKeyDown(event: KeyboardEvent) {
    const index = tabs.findIndex((tab) => tab.value === value)
    const target = {
      ArrowRight: (index + 1) % tabs.length,
      ArrowLeft: (index - 1 + tabs.length) % tabs.length,
      Home: 0,
      End: tabs.length - 1,
    }[event.key]
    const next = target === undefined ? undefined : tabs[target]
    if (next) {
      event.preventDefault()
      onChange(next.value)
      buttons.current.get(next.value)?.focus()
    }
  }

  return (
    <div className={styles.root}>
      <div role="tablist" aria-label={label} className={styles.list}>
        {tabs.map((tab) => (
          <button
            key={tab.value}
            ref={(element) => {
              if (element) {
                buttons.current.set(tab.value, element)
              } else {
                buttons.current.delete(tab.value)
              }
            }}
            type="button"
            role="tab"
            id={tabId(tab.value)}
            aria-selected={tab.value === value}
            aria-controls={`${base}-panel`}
            tabIndex={tab.value === value ? 0 : -1}
            className={styles.tab}
            onKeyDown={onKeyDown}
            onClick={() => {
              onChange(tab.value)
            }}
          >
            {tab.label}
            {tab.count !== undefined && <span className={styles.count}>{tab.count}</span>}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`${base}-panel`} aria-labelledby={tabId(value)} className={styles.panel}>
        {children}
      </div>
    </div>
  )
}

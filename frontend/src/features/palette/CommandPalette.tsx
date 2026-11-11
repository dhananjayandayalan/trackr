import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams, useSearch } from '@tanstack/react-router'
import { useDeferredValue, useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { useShortcut } from '@/app/keyboard.ts'
import { issueQuery, transitionsQuery } from '@/features/issue/queries.ts'
import { useTransitionIssue } from '@/features/issue/mutations.ts'
import { projectsQuery } from '@/features/projects/queries.ts'
import { suggestQuery } from '@/features/search/queries.ts'
import { focusOnMount } from '@/shared/lib/focus.ts'
import { Kbd } from '@/shared/ui/Kbd.tsx'
import { fuzzyFilter } from './fuzzy.ts'
import styles from './CommandPalette.module.css'

interface Item {
  id: string
  group: 'Issues' | 'Actions' | 'Go to'
  label: string
  hint?: string
  run: () => void
}

const ISSUE_KEY = /^[A-Za-z]{2,6}-\d+$/

/**
 * The command palette (00-product §3.13): Ctrl/Cmd+K anywhere, even while typing. One input, one listbox, three
 * sources: issues from /search/suggest (the server matches), places to go and actions for the issue on screen
 * (matched here, fuzzily). A native <dialog>: showModal makes the page inert and Esc closes it.
 */
export function CommandPalette() {
  const dialog = useRef<HTMLDialogElement>(null)
  const [open, setOpen] = useState(false)

  useShortcut(
    'mod+k',
    () => {
      setOpen((was) => !was)
    },
    { whenTyping: true },
  )

  useEffect(() => {
    const element = dialog.current
    if (!element) {
      return
    }
    if (open && !element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    } else if (!open && element.open) {
      if (typeof element.close === 'function') element.close()
      else element.removeAttribute('open')
    }
  }, [open])

  return (
    <dialog
      ref={dialog}
      className={styles.dialog}
      aria-label="Command palette"
      onClose={() => {
        setOpen(false)
      }}
    >
      {open && (
        <Palette
          onDone={() => {
            setOpen(false)
          }}
        />
      )}
    </dialog>
  )
}

function Palette({ onDone }: { onDone: () => void }) {
  const id = useId()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const deferred = useDeferredValue(query.trim())

  // BLOCK 1: where we are — the project in the URL, and the issue on screen (its page, or the side panel)
  const params = useParams({ strict: false })
  const search = useSearch({ strict: false })
  const projectKey = params.projectKey
  const issueKey =
    params.issueKey ?? ('issue' in search && typeof search.issue === 'string' ? search.issue : undefined)

  const projects = useQuery(projectsQuery())
  const suggestions = useQuery({ ...suggestQuery(deferred), enabled: deferred.length >= 2 })
  const issue = useQuery({ ...issueQuery(issueKey ?? ''), enabled: issueKey !== undefined })
  const transitions = useQuery({ ...transitionsQuery(issueKey ?? ''), enabled: issueKey !== undefined })
  const transition = useTransitionIssue(issueKey ?? '')

  const go = (item: Omit<Item, 'group' | 'run'>, to: () => void): Item => ({
    ...item,
    group: 'Go to',
    run: to,
  })

  // BLOCK 2: the static items, then fuzzy-filtered
  const places: Item[] = [
    go({ id: 'home', label: 'Your work' }, () => void navigate({ to: '/' })),
    go({ id: 'projects', label: 'Projects' }, () => void navigate({ to: '/projects' })),
    go({ id: 'issues', label: 'Issues and filters' }, () => void navigate({ to: '/issues' })),
    go({ id: 'dashboards', label: 'Dashboards' }, () => void navigate({ to: '/dashboards' })),
    ...(projects.data ?? []).flatMap((project) =>
      (
        [
          ['board', 'board', '/p/$projectKey/board'],
          ['backlog', 'backlog', '/p/$projectKey/backlog'],
          ['timeline', 'timeline', '/p/$projectKey/timeline'],
          ['reports', 'reports', '/p/$projectKey/reports'],
        ] as const
      ).map(([suffix, label, to]) =>
        go(
          {
            id: `${project.key}-${suffix}`,
            label: `${project.key} ${label}`,
            hint: project.key === projectKey ? 'this project' : project.name,
          },
          () => void navigate({ to, params: { projectKey: project.key } }),
        ),
      ),
    ),
    ...(projectKey
      ? [
          go(
            { id: 'settings', label: `${projectKey} workflow settings` },
            () =>
              void navigate({
                to: '/p/$projectKey/settings/$section',
                params: { projectKey, section: 'workflow' },
              }),
          ),
        ]
      : []),
  ]
  const actions: Item[] =
    issueKey && issue.data
      ? (transitions.data ?? []).map((status) => ({
          id: `move-${status.id}`,
          group: 'Actions' as const,
          label: `Move ${issueKey} to ${status.name}`,
          hint: issue.data.status.name,
          run: () => {
            transition.mutate(status)
          },
        }))
      : []

  // BLOCK 3: issues — a typed key first ("MAR-12" opens it even if the index hasn't seen it yet), then suggestions
  const typedKey = ISSUE_KEY.test(query.trim()) ? query.trim().toUpperCase() : null
  const openIssue = (key: string) => {
    void navigate({
      to: '/p/$projectKey/issues/$issueKey',
      params: { projectKey: key.split('-')[0] ?? '', issueKey: key },
    })
  }
  const issues: Item[] = [
    ...(typedKey
      ? [
          {
            id: `open-${typedKey}`,
            group: 'Issues' as const,
            label: `Open ${typedKey}`,
            run: () => openIssue(typedKey),
          },
        ]
      : []),
    ...(deferred.length >= 2 ? (suggestions.data?.issues ?? []) : [])
      .filter((suggestion) => suggestion.key !== typedKey)
      .map((suggestion) => ({
        id: `issue-${suggestion.key}`,
        group: 'Issues' as const,
        label: `${suggestion.key} ${suggestion.summary}`,
        run: () => {
          openIssue(suggestion.key)
        },
      })),
  ]

  const items = [
    ...issues,
    ...fuzzyFilter(query, actions, (item) => item.label),
    ...fuzzyFilter(query, places, (item) => `${item.label} ${item.hint ?? ''}`).slice(0, query ? 8 : 6),
  ]
  const current = Math.min(active, items.length - 1)

  const run = (item: Item | undefined) => {
    if (item) {
      onDone()
      item.run()
    }
  }

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : -1
      setActive((current + step + items.length) % Math.max(1, items.length))
    } else if (event.key === 'Enter') {
      event.preventDefault()
      run(items[current])
    }
  }

  // a heading before the first item of each group
  const rows = items.map((item, index) => ({
    item,
    index,
    heading: index === 0 || items[index - 1]?.group !== item.group,
  }))
  return (
    <div className={styles.palette}>
      <input
        role="combobox"
        aria-expanded="true"
        aria-controls={`${id}-list`}
        aria-activedescendant={items[current] ? `${id}-${items[current].id}` : undefined}
        aria-label="Search issues, places and actions"
        className={styles.input}
        placeholder="Jump to an issue, a place, or run an action…"
        value={query}
        // the palette exists only while open: focus goes straight to its input
        ref={focusOnMount}
        onChange={(event) => {
          setQuery(event.target.value)
          setActive(0)
        }}
        onKeyDown={onKeyDown}
      />
      <ul id={`${id}-list`} role="listbox" aria-label="Results" className={styles.list}>
        {items.length === 0 && <li className={styles.empty}>No matches</li>}
        {rows.map(({ item, index, heading }) => {
          return [
            heading && (
              <li key={`group-${item.group}`} role="presentation" className={styles.group}>
                {item.group}
              </li>
            ),
            <li
              key={item.id}
              id={`${id}-${item.id}`}
              role="option"
              aria-selected={index === current}
              className={styles.option}
              onMouseDown={(event) => {
                event.preventDefault()
                run(item)
              }}
              onMouseMove={() => {
                setActive(index)
              }}
            >
              <span>{item.label}</span>
              {item.hint && <span className={styles.hint}>{item.hint}</span>}
            </li>,
          ]
        })}
      </ul>
      <p className={styles.footer}>
        <Kbd>↑</Kbd> <Kbd>↓</Kbd> to move · <Kbd>Enter</Kbd> to run · <Kbd>Esc</Kbd> to close
      </p>
    </div>
  )
}

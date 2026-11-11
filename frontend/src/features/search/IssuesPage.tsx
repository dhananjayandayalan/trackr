import { useSuspenseQuery } from '@tanstack/react-query'
import { getRouteApi } from '@tanstack/react-router'
import { useDeferredValue, useId, useState, type SubmitEvent } from 'react'
import { messageOf } from '@/shared/api/errors.ts'
import { text } from '@/shared/lib/forms.ts'
import type { FilterView } from '@/shared/api/types.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Dialog } from '@/shared/ui/Dialog.tsx'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'
import { Field } from '@/shared/ui/Field.tsx'
import { Segmented } from '@/shared/ui/Segmented.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { BulkBar } from './BulkBar.tsx'
import { ExportButton } from './ExportButton.tsx'
import { FilterPills } from './FilterPills.tsx'
import { useCreateFilter, useStarFilter } from './mutations.ts'
import { filtersQuery } from './queries.ts'
import { ResultsTable } from './ResultsTable.tsx'
import { TqlEditor } from './TqlEditor.tsx'
import { defaultFilters, pillsToTql, sortOf, withSort, type Pills, type Sort } from './tql.ts'
import { useSearch } from './useSearch.ts'
import styles from './IssuesPage.module.css'

const route = getRouteApi('/_authed/_app/issues')

/**
 * Issues & filters (00-product §3.11). Everything that decides the results lives in the URL — mode, query,
 * pills, sort, the chosen filter — so a search can be bookmarked, shared and reloaded, and Back undoes a step.
 * Basic mode builds TQL from pills; query mode is the TQL itself. Either way one string goes to /search.
 */
export function IssuesPage() {
  const search = route.useSearch()
  const navigate = route.useNavigate()
  const { data: savedFilters } = useSuspenseQuery(filtersQuery())
  const mode = search.mode ?? 'basic'
  const pills: Pills = {
    projects: search.projects ?? [],
    types: search.types ?? [],
    statuses: search.statuses ?? [],
    assignees: search.assignees ?? [],
    priorities: search.priorities ?? [],
    text: search.text ?? '',
  }
  const basicSort: Sort | null = search.sort
    ? {
        field: search.sort.split('.')[0] ?? 'updated',
        direction: search.sort.endsWith('.asc') ? 'asc' : 'desc',
      }
    : null

  // BLOCK 1: the one query string — and a deferred copy for the request, so typing in the pills' search box
  // stays instant while the results catch up
  const tql = mode === 'query' ? (search.q ?? '') : withSort(pillsToTql(pills), basicSort)
  const deferredTql = useDeferredValue(tql)
  const results = useSearch(deferredTql)
  const sort = sortOf(tql)

  // the selection belongs to one query: new results start with nothing checked
  const [selection, setSelection] = useState<{ tql: string; keys: ReadonlySet<string> }>({
    tql,
    keys: new Set(),
  })
  const selected = selection.tql === tql ? selection.keys : new Set<string>()
  const selectedRows = results.rows.filter((row) => selected.has(row.key))

  const setPills = (next: Pills) => {
    void navigate({
      search: (prev) => ({
        ...prev,
        filter: undefined,
        projects: next.projects.length ? next.projects : undefined,
        types: next.types.length ? next.types : undefined,
        statuses: next.statuses.length ? next.statuses : undefined,
        assignees: next.assignees.length ? next.assignees : undefined,
        priorities: next.priorities.length ? next.priorities : undefined,
        text: next.text || undefined,
      }),
      replace: true,
    })
  }

  const runQuery = (q: string, filter?: string) => {
    void navigate({ search: { mode: 'query', q, ...(filter ? { filter } : {}) } })
  }

  const onSort = (next: Sort) => {
    if (mode === 'query') {
      runQuery(withSort(search.q ?? '', next), search.filter)
    } else {
      void navigate({
        search: (prev) => ({ ...prev, sort: `${next.field}.${next.direction}` }),
        replace: true,
      })
    }
  }

  const [saving, setSaving] = useState(false)

  return (
    <div className={styles.root}>
      <FilterNav selected={search.filter} onPick={(filter) => runQuery(filter.tql, filter.id)} />

      <section className={styles.main} aria-labelledby="issues-title">
        {/* BLOCK 2: header — title, mode switch, save and export */}
        <header className={styles.header}>
          <div>
            <p className={styles.eyebrow}>Issues</p>
            <h1 id="issues-title">{filterName(search.filter, savedFilters) ?? 'Search issues'}</h1>
          </div>
          <div className={styles.actions}>
            <Segmented
              label="Mode"
              value={mode}
              options={[
                { value: 'basic', label: 'Basic' },
                { value: 'query', label: 'TQL' },
              ]}
              onChange={(next) => {
                // basic → query keeps the query the pills made; query → basic starts from empty pills (TQL can
                // say things pills can't, so there is no way back)
                void navigate({ search: next === 'query' ? { mode: 'query', q: tql } : { mode: 'basic' } })
              }}
            />
            <Button
              icon="star"
              size="sm"
              onClick={() => {
                setSaving(true)
              }}
            >
              Save filter
            </Button>
            <ExportButton tql={tql} />
          </div>
        </header>

        {mode === 'basic' ? (
          <FilterPills pills={pills} onChange={setPills} />
        ) : (
          // keyed by the URL's query: a filter picked in the nav replaces what was being typed
          <QueryBar
            key={search.q ?? ''}
            initial={search.q ?? ''}
            error={results.tqlError}
            onRun={(q) => runQuery(q)}
          />
        )}

        {/* BLOCK 3: the count, announced politely after each search */}
        <p className={styles.count} aria-live="polite">
          {results.isLoading
            ? 'Searching…'
            : `${results.total.toLocaleString()} issue${results.total === 1 ? '' : 's'} · ${String(results.tookMs)} ms`}
        </p>

        {results.error ? (
          <EmptyState icon="close" title="Search failed">
            {messageOf(results.error)}
          </EmptyState>
        ) : !results.isLoading && results.rows.length === 0 && !results.tqlError ? (
          <EmptyState icon="search" title="No issues match">
            Try fewer filters, or another query.
          </EmptyState>
        ) : (
          <ResultsTable
            rows={results.rows}
            sort={sort}
            onSort={onSort}
            selected={selected}
            onSelect={(keys) => {
              setSelection({ tql, keys })
            }}
            onNearEnd={results.fetchNextPage}
            stale={results.isStale}
            bulkBar={
              <BulkBar
                rows={selectedRows}
                onDone={() => {
                  setSelection({ tql, keys: new Set() })
                }}
              />
            }
          />
        )}
      </section>

      <SaveFilterDialog
        open={saving}
        tql={tql}
        onClose={() => {
          setSaving(false)
        }}
        onSaved={(filter) => {
          setSaving(false)
          runQuery(filter.tql, filter.id)
        }}
      />
    </div>
  )
}

/** The editor plus a Run button; the typed text is local until it runs (then it's the URL's). */
function QueryBar({
  initial,
  error,
  onRun,
}: {
  initial: string
  error: ReturnType<typeof useSearch>['tqlError']
  onRun: (q: string) => void
}) {
  const [draft, setDraft] = useState(initial)
  return (
    <form
      className={styles.queryBar}
      onSubmit={(event: SubmitEvent<HTMLFormElement>) => {
        event.preventDefault()
        onRun(draft)
      }}
    >
      <div className={styles.editor}>
        <TqlEditor
          value={draft}
          onChange={setDraft}
          onSubmit={onRun}
          error={draft === initial ? error : null}
        />
      </div>
      <Button type="submit" variant="primary">
        Search
      </Button>
    </form>
  )
}

function filterName(id: string | undefined, saved: readonly FilterView[]): string | undefined {
  return (defaultFilters.find((filter) => filter.id === id) ?? saved.find((filter) => filter.id === id))?.name
}

// BLOCK 4: the filter nav — the built-in filters, then starred and other saved ones
function FilterNav({
  selected,
  onPick,
}: {
  selected: string | undefined
  onPick: (filter: FilterView | DefaultFilter) => void
}) {
  const { data: saved } = useSuspenseQuery(filtersQuery())
  const star = useStarFilter()
  const starred = saved.filter((filter) => filter.starred)
  const others = saved.filter((filter) => !filter.starred)

  const item = (filter: FilterView | DefaultFilter, savedFilter?: FilterView) => (
    <li key={filter.id} className={styles.filterItem}>
      <button
        type="button"
        className={styles.filter}
        aria-current={selected === filter.id ? 'true' : undefined}
        onClick={() => {
          onPick(filter)
        }}
      >
        {filter.name}
      </button>
      {savedFilter?.mine && (
        <button
          type="button"
          className={styles.star}
          aria-pressed={savedFilter.starred}
          aria-label={`${savedFilter.starred ? 'Unstar' : 'Star'} ${savedFilter.name}`}
          onClick={() => {
            star.mutate({ id: savedFilter.id, starred: !savedFilter.starred })
          }}
        >
          {savedFilter.starred ? '★' : '☆'}
        </button>
      )}
    </li>
  )

  return (
    <nav className={styles.nav} aria-label="Filters">
      <h2 className={styles.navTitle}>Default filters</h2>
      <ul role="list" className={styles.filters}>
        {defaultFilters.map((filter) => item(filter))}
      </ul>
      {starred.length > 0 && (
        <>
          <h2 className={styles.navTitle}>Starred</h2>
          <ul role="list" className={styles.filters}>
            {starred.map((filter) => item(filter, filter))}
          </ul>
        </>
      )}
      {others.length > 0 && (
        <>
          <h2 className={styles.navTitle}>Saved filters</h2>
          <ul role="list" className={styles.filters}>
            {others.map((filter) => item(filter, filter))}
          </ul>
        </>
      )}
    </nav>
  )
}

type DefaultFilter = (typeof defaultFilters)[number]

function SaveFilterDialog({
  open,
  tql,
  onClose,
  onSaved,
}: {
  open: boolean
  tql: string
  onClose: () => void
  onSaved: (filter: FilterView) => void
}) {
  const create = useCreateFilter()
  const starId = useId()
  return (
    <Dialog open={open} onClose={onClose} title="Save filter" description={tql || 'All issues you can see'}>
      <form
        className={styles.saveForm}
        onSubmit={(event: SubmitEvent<HTMLFormElement>) => {
          event.preventDefault()
          const form = new FormData(event.currentTarget)
          create.mutate(
            { name: text(form, 'name').trim(), tql, starred: form.get('starred') === 'on' },
            { onSuccess: onSaved },
          )
        }}
      >
        <Field label="Name" error={create.error ? messageOf(create.error) : undefined}>
          {(control) => <TextInput {...control} name="name" required maxLength={80} />}
        </Field>
        <label htmlFor={starId} className={styles.checkbox}>
          <input id={starId} type="checkbox" name="starred" defaultChecked />
          Star it (show it at the top)
        </label>
        <div className={styles.dialogActions}>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={create.isPending}>
            Save
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

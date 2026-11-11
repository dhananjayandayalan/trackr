/**
 * Basic mode's pills → a TQL string (05-search §3). A pure function: the pills are data, the query is text, and
 * the test pins down every quoting and grouping rule. The api parses what we build, so a wrong string here is a
 * 400 with a position, never a silent wrong result.
 */

// BLOCK 1: the pills' state
export interface Pills {
  projects: string[]
  types: string[]
  /** status names, e.g. "In review" */
  statuses: string[]
  /** display names, `currentUser()`, or UNASSIGNED */
  assignees: string[]
  priorities: string[]
  text: string
}

export const UNASSIGNED = 'EMPTY'
export const CURRENT_USER = 'currentUser()'

export const emptyPills: Pills = {
  projects: [],
  types: [],
  statuses: [],
  assignees: [],
  priorities: [],
  text: '',
}

// BLOCK 2: values — bare words stay bare (type = Bug), anything else is a quoted string with \" and \\ escaped.
// Functions and issue keys pass through: currentUser() would be a string "currentUser()" if quoted.
const bare = /^[A-Za-z_][A-Za-z0-9_]*$/
const fn = /^[A-Za-z]+\(\)$/

export function tqlValue(value: string): string {
  return bare.test(value) || fn.test(value) ? value : quote(value)
}

/** A TQL string literal; `text ~` only takes strings, so its value is always quoted. */
export function quote(value: string): string {
  return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`
}

// one value: field = v; several: field in (a, b)
function clause(field: string, values: readonly string[]): string | null {
  if (values.length === 0) {
    return null
  }
  const [only] = values
  return values.length === 1 && only !== undefined
    ? `${field} = ${tqlValue(only)}`
    : `${field} in (${values.map(tqlValue).join(', ')})`
}

// BLOCK 3: pills → query. Clauses are ANDed; within a pill the values are alternatives. "Unassigned" isn't a
// value but IS EMPTY, so with names next to it the pill becomes (assignee in (…) OR assignee IS EMPTY).
export function pillsToTql(pills: Pills): string {
  const parts: string[] = []
  const add = (part: string | null) => {
    if (part) {
      parts.push(part)
    }
  }
  add(clause('project', pills.projects))
  add(clause('type', pills.types))
  add(clause('status', pills.statuses))
  add(clause('priority', pills.priorities))

  const people = pills.assignees.filter((value) => value !== UNASSIGNED)
  const unassigned = pills.assignees.includes(UNASSIGNED)
  const named = clause('assignee', people)
  if (named && unassigned) {
    parts.push(`(${named} OR assignee IS EMPTY)`)
  } else if (unassigned) {
    parts.push('assignee IS EMPTY')
  } else {
    add(named)
  }

  const text = pills.text.trim()
  if (text) {
    parts.push(`text ~ ${quote(text)}`)
  }
  return parts.join(' AND ')
}

// BLOCK 4: sorting — the table's headers rewrite the query's ORDER BY; the rest of the query stays as typed
export interface Sort {
  field: string
  direction: 'asc' | 'desc'
}

const orderBy = /\s*\bORDER\s+BY\b[\s\S]*$/i

/** The ORDER BY's first key, if the query has one: what the table marks with aria-sort. */
export function sortOf(tql: string): Sort | null {
  const match = /\bORDER\s+BY\s+([A-Za-z]+)(?:\s+(ASC|DESC))?/i.exec(tql)
  if (!match?.[1]) {
    return null
  }
  return { field: match[1], direction: match[2]?.toLowerCase() === 'desc' ? 'desc' : 'asc' }
}

export function withSort(tql: string, sort: Sort | null): string {
  const filter = tql.replace(orderBy, '').trim()
  if (!sort) {
    return filter
  }
  const order = `ORDER BY ${sort.field} ${sort.direction.toUpperCase()}`
  return filter ? `${filter} ${order}` : order
}

// BLOCK 5: the default filters of 00-product §3.11 — saved filters' built-in siblings
export const defaultFilters = [
  { id: 'my-open', name: 'My open issues', tql: 'assignee = currentUser() AND statusCategory != done' },
  { id: 'reported', name: 'Reported by me', tql: 'reporter = currentUser()' },
  { id: 'all', name: 'All issues', tql: '' },
  { id: 'open', name: 'Open issues', tql: 'statusCategory != done' },
  { id: 'done', name: 'Done issues', tql: 'statusCategory = done' },
  { id: 'recent', name: 'Created recently', tql: 'created >= -7d' },
] as const

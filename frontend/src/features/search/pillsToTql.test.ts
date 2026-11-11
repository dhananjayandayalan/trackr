import { describe, expect, it } from 'vitest'
import { emptyPills, pillsToTql, sortOf, tqlValue, UNASSIGNED, withSort, type Pills } from './tql.ts'

const pills = (overrides: Partial<Pills>): Pills => ({ ...emptyPills, ...overrides })

describe('pillsToTql', () => {
  it('is empty without pills: everything I can see', () => {
    expect(pillsToTql(emptyPills)).toBe('')
  })

  it('uses = for one value and in (…) for several, ANDed across pills', () => {
    expect(pillsToTql(pills({ projects: ['MAR'], types: ['bug', 'task'] }))).toBe(
      'project = MAR AND type in (bug, task)',
    )
  })

  it('quotes values with spaces and escapes quotes and backslashes', () => {
    expect(pillsToTql(pills({ statuses: ['In review', 'Done'] }))).toBe('status in ("In review", Done)')
    expect(tqlValue('say "hi"')).toBe('"say \\"hi\\""')
    expect(tqlValue('a\\b')).toBe('"a\\\\b"')
  })

  it('keeps functions bare', () => {
    expect(pillsToTql(pills({ assignees: ['currentUser()'] }))).toBe('assignee = currentUser()')
  })

  it('turns Unassigned into IS EMPTY, grouped with names by OR', () => {
    expect(pillsToTql(pills({ assignees: [UNASSIGNED] }))).toBe('assignee IS EMPTY')
    expect(pillsToTql(pills({ assignees: ['Ada Lovelace', UNASSIGNED], priorities: ['high'] }))).toBe(
      'priority = high AND (assignee = "Ada Lovelace" OR assignee IS EMPTY)',
    )
  })

  it('always quotes the text, which TQL only accepts as a string', () => {
    expect(pillsToTql(pills({ text: '  checkout  ' }))).toBe('text ~ "checkout"')
  })
})

describe('sorting', () => {
  it('reads the first ORDER BY key', () => {
    expect(sortOf('project = MAR ORDER BY priority DESC, updated DESC')).toEqual({
      field: 'priority',
      direction: 'desc',
    })
    expect(sortOf('order by key')).toEqual({ field: 'key', direction: 'asc' })
    expect(sortOf('project = MAR')).toBeNull()
  })

  it('replaces the ORDER BY and leaves the filter as typed', () => {
    expect(withSort('project = MAR ORDER BY key', { field: 'updated', direction: 'desc' })).toBe(
      'project = MAR ORDER BY updated DESC',
    )
    expect(withSort('', { field: 'key', direction: 'asc' })).toBe('ORDER BY key ASC')
    expect(withSort('type = bug order by key desc', null)).toBe('type = bug')
  })
})

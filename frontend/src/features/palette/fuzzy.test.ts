import { describe, expect, it } from 'vitest'
import { fuzzyFilter, fuzzyScore } from './fuzzy.ts'

describe('fuzzy', () => {
  it('matches letters in order, not necessarily together', () => {
    expect(fuzzyScore('gb', 'Go to board')).toBeGreaterThan(0)
    expect(fuzzyScore('bg', 'Go to board')).toBe(-1)
    expect(fuzzyScore('', 'anything')).toBe(0)
  })

  it('ranks word starts and runs of letters first', () => {
    const items = ['Settings', 'Go to backlog', 'Go to board', 'Burndown report']
    expect(fuzzyFilter('board', items, (item) => item)).toEqual(['Go to board'])
    expect(fuzzyFilter('b', items, (item) => item)[0]).toBe('Go to board')
  })
})

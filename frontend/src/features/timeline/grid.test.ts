import { describe, expect, it } from 'vitest'
import { addDays, barSpan, gridFor, mondayOf, movedDue, todayPercent, weekIndex, weekStarts } from './grid.ts'

describe('timeline grid', () => {
  it('finds the Monday of a week, across month and year ends', () => {
    expect(mondayOf('2026-11-10')).toBe('2026-11-09')
    expect(mondayOf('2026-11-09')).toBe('2026-11-09')
    expect(mondayOf('2026-11-15')).toBe('2026-11-09')
    expect(mondayOf('2027-01-01')).toBe('2026-12-28')
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01')
  })

  it('starts a week before the earliest date and runs at least the minimum', () => {
    const grid = gridFor(['2026-10-19', '2027-01-15', null], '2026-11-10', 4)
    expect(grid.start).toBe('2026-10-12')
    // 12 Oct → 29 Jan (15 Jan + 14 days) is 110 days: 16 weeks
    expect(grid.weeks).toBe(16)
    expect(weekStarts(grid).slice(0, 2)).toEqual(['2026-10-12', '2026-10-19'])
  })

  it('maps dates to week columns, clamped to the grid', () => {
    const grid = { start: '2026-10-12', weeks: 10 }
    expect(weekIndex(grid, '2026-10-12')).toBe(0)
    expect(weekIndex(grid, '2026-10-18')).toBe(0)
    expect(weekIndex(grid, '2026-10-19')).toBe(1)
    expect(weekIndex(grid, '2025-01-01')).toBe(0)
    expect(weekIndex(grid, '2030-01-01')).toBe(9)
  })

  it('spans bars over grid lines; line 2 is the first week', () => {
    const grid = { start: '2026-10-12', weeks: 10 }
    expect(barSpan(grid, '2026-10-19', '2026-11-27')).toEqual({ start: 3, end: 9 })
    // only a due date: the week that ends on it
    expect(barSpan(grid, null, '2026-10-21')).toEqual({ start: 2, end: 4 })
    expect(barSpan(grid, '2026-10-12', null)).toEqual({ start: 2, end: 3 })
    expect(barSpan(grid, null, null)).toBeNull()
  })

  it('puts today in the middle of its day', () => {
    expect(todayPercent({ start: '2026-11-09', weeks: 1 }, '2026-11-09')).toBeCloseTo((0.5 / 7) * 100)
  })

  it('moves a due date by whole weeks, never before the start', () => {
    expect(movedDue('2026-11-27', 2)).toBe('2026-12-11')
    expect(movedDue('2026-11-27', -10, '2026-11-02')).toBe('2026-11-02')
  })
})

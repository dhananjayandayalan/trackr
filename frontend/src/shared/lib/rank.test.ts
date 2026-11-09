import { describe, expect, it } from 'vitest'
import vectors from '../../../../docs/spec/fixtures/rank-vectors.json'
import { between, compareRanks, DIGITS, nBetween } from './rank.ts'

// The same file the Java RankTest wrote and checks: one set of answers, two implementations.
interface Vector {
  before: string | null
  after: string | null
  n: number
  result: string[]
}

describe('rank — shared vectors with Rank.java', () => {
  it('uses the same alphabet', () => {
    expect(DIGITS).toBe(vectors.digits)
  })

  it.each(vectors.cases as Vector[])(
    'nBetween($before, $after, $n) = $result',
    ({ before, after, n, result }) => {
      expect(nBetween(before, after, n)).toEqual(result)
      if (n === 1) {
        expect(between(before, after)).toBe(result[0])
      }
    },
  )
})

describe('rank — properties', () => {
  it('is strictly between its neighbours, many times over', () => {
    let low: string | null = null
    let high: string | null = null
    // keep narrowing the same gap from both sides: the ranks get longer, never equal, never out of order
    for (let i = 0; i < 200; i++) {
      const middle = between(low, high)
      if (low !== null) expect(compareRanks(low, middle)).toBe(-1)
      if (high !== null) expect(compareRanks(middle, high)).toBe(-1)
      if (i % 2 === 0) low = middle
      else high = middle
    }
  })

  it('rejects ranks the server would never store', () => {
    expect(() => between('V0', null)).toThrow(RangeError)
    expect(() => between('', null)).toThrow(RangeError)
    expect(() => between('V-', null)).toThrow(RangeError)
    expect(() => between('W', 'V')).toThrow(RangeError)
  })

  it('compares like Postgres COLLATE "C", not like localeCompare', () => {
    expect(['a', 'B', 'Z', '1'].sort(compareRanks)).toEqual(['1', 'B', 'Z', 'a'])
  })
})

import { describe, expect, it } from 'vitest'
import { bandScale, linearScale, maxOf, niceTicks } from './scale.ts'

describe('linearScale', () => {
  it('maps the domain onto the range, also reversed (SVG y grows downward)', () => {
    const y = linearScale([0, 80], [200, 0])
    expect(y(0)).toBe(200)
    expect(y(80)).toBe(0)
    expect(y(20)).toBe(150)
  })

  it('maps a flat domain to the start of the range instead of dividing by zero', () => {
    expect(linearScale([5, 5], [0, 100])(5)).toBe(0)
  })

  it('offers round ticks across the domain', () => {
    expect(linearScale([0, 79], [0, 1]).ticks(4)).toEqual([0, 20, 40, 60])
  })
})

describe('niceTicks', () => {
  it('uses steps of 1, 2 or 5 times a power of ten', () => {
    expect(niceTicks(0, 10, 5)).toEqual([0, 2, 4, 6, 8, 10])
    expect(niceTicks(0, 1, 4)).toEqual([0, 0.5, 1])
    expect(niceTicks(0, 0)).toEqual([0])
  })
})

describe('bandScale', () => {
  it('gives each category an equal band with padding around it', () => {
    const x = bandScale(['S1', 'S2', 'S3', 'S4'], [0, 400], 0.5)
    expect(x.step).toBe(100)
    expect(x.bandwidth).toBe(50)
    expect(x('S1')).toBe(25)
    expect(x('S3')).toBe(225)
  })

  it('has no bands for no categories', () => {
    expect(bandScale([], [0, 100]).bandwidth).toBe(0)
  })
})

describe('maxOf', () => {
  it('skips missing values and never goes below the minimum', () => {
    expect(maxOf([3, null, 9, undefined])).toBe(9)
    expect(maxOf([0, 0])).toBe(1)
  })
})

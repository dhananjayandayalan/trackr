import { describe, expect, it } from 'vitest'
import { formatMinutes, parseDuration } from './durations.ts'

describe('durations', () => {
  it('formats minutes the way people write them', () => {
    expect(formatMinutes(0)).toBe('0m')
    expect(formatMinutes(45)).toBe('45m')
    expect(formatMinutes(90)).toBe('1h 30m')
    expect(formatMinutes(480)).toBe('1d')
    expect(formatMinutes(2400 + 60)).toBe('1w 1h')
  })

  it('parses what people type', () => {
    expect(parseDuration('1h 30m')).toBe(90)
    expect(parseDuration('90m')).toBe(90)
    expect(parseDuration('90')).toBe(90)
    expect(parseDuration('1.5h')).toBe(90)
    expect(parseDuration('2d')).toBe(960)
    expect(parseDuration(' 1H30M ')).toBe(90)
  })

  it('refuses what it would have to guess', () => {
    expect(parseDuration('')).toBeNull()
    expect(parseDuration('1 30')).toBeNull()
    expect(parseDuration('soon')).toBeNull()
    expect(parseDuration('0m')).toBeNull()
    expect(parseDuration('2h and a bit')).toBeNull()
  })
})

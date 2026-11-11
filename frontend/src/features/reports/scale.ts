import type { CSSProperties } from 'react'

/**
 * Scales: the one idea behind every chart. A scale is a function from data space (points, days, sprint names)
 * to screen space (SVG user units). No chart library — these few lines are the part of d3 the charts need.
 */

// BLOCK 1: linear — y = range0 + (x - domain0) × (range span / domain span). Reversed ranges work too: SVG's y
// grows downward, so a chart passes [height, 0] to put 0 at the bottom.
export interface LinearScale {
  (value: number): number
  domain: readonly [number, number]
  range: readonly [number, number]
  /** `count` round values across the domain, for gridlines and axis labels: 0, 20, 40, … */
  ticks: (count?: number) => number[]
}

export function linearScale(
  domain: readonly [number, number],
  range: readonly [number, number],
): LinearScale {
  const [d0, d1] = domain
  const [r0, r1] = range
  const span = d1 - d0
  // a flat domain (every value 0) would divide by zero: map everything to the start of the range
  const scale = ((value: number) => (span === 0 ? r0 : r0 + ((value - d0) * (r1 - r0)) / span)) as LinearScale
  scale.domain = domain
  scale.range = range
  scale.ticks = (count = 5) => niceTicks(d0, d1, count)
  return scale
}

/** Steps of 1, 2 or 5 × a power of ten, the way people label axes. */
export function niceTicks(min: number, max: number, count = 5): number[] {
  if (max <= min) {
    return [min]
  }
  const raw = (max - min) / Math.max(1, count)
  const power = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 5, 10].map((m) => m * power).find((candidate) => candidate >= raw) ?? raw
  const ticks: number[] = []
  for (let tick = Math.ceil(min / step) * step; tick <= max + step / 1e6; tick += step) {
    // round away floating-point dust (0.30000000000000004)
    ticks.push(Math.round(tick * 1e6) / 1e6)
  }
  return ticks
}

// BLOCK 2: band — n equal bands for n categories, with padding between them (bar charts: one band per sprint)
export interface BandScale<T> {
  (value: T): number
  bandwidth: number
  step: number
}

/** `padding` is the share of each step left empty (0–1), split evenly on both sides of the band. */
export function bandScale<T>(
  domain: readonly T[],
  range: readonly [number, number],
  padding = 0.2,
): BandScale<T> {
  const [r0, r1] = range
  const step = domain.length === 0 ? 0 : (r1 - r0) / domain.length
  const bandwidth = step * (1 - padding)
  const index = new Map(domain.map((value, i) => [value, i]))
  const scale = ((value: T) => r0 + (index.get(value) ?? 0) * step + (step - bandwidth) / 2) as BandScale<T>
  scale.bandwidth = bandwidth
  scale.step = step
  return scale
}

/** The largest value, for a domain's upper end; at least `min`, so an all-zero chart still has an axis. */
export function maxOf(values: Iterable<number | null | undefined>, min = 1): number {
  let max = min
  for (const value of values) {
    if (value != null && value > max) {
      max = value
    }
  }
  return max
}

// BLOCK 3: share — for bars that are just boxes: a flex row per item, the bar's width a custom property set
// from the data (`--share: 40%`). No SVG needed when the only axis is "how much of the whole".
export function share(part: number, whole: number): CSSProperties {
  const style: Record<string, string> = { '--share': `${String(whole === 0 ? 0 : (part / whole) * 100)}%` }
  return style
}

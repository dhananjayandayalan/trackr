import type { Burndown as BurndownData } from '@/shared/api/types.ts'
import { dayLabel } from '@/shared/lib/dates.ts'
import { linearScale, maxOf } from './scale.ts'
import styles from './Charts.module.css'

const W = 640
const H = 280
const M = { top: 16, right: 20, bottom: 36, left: 44 }

const points = (value: number) => `${String(value)} pt${value === 1 ? '' : 's'}`

/**
 * Remaining points per working day against the ideal line (00-product §3.10), in plain SVG. The viewBox makes it
 * scale with its box; the scales map days and points to that box. Colour never carries meaning alone: the lines
 * differ in style too (solid steps vs dashes), the legend names them, and the table under the chart has the numbers.
 */
export function Burndown({ data }: { data: BurndownData }) {
  const days = data.days
  const known = days.filter((day) => day.remaining != null)
  const last = known.at(-1)

  // BLOCK 1: scales — x by day index (working days are evenly spaced, weekends aren't on the axis), y by points
  const x = linearScale([0, Math.max(1, days.length - 1)], [M.left, W - M.right])
  const top = maxOf([data.committed, ...days.map((d) => d.remaining), ...days.map((d) => d.ideal)])
  const y = linearScale([0, top], [H - M.bottom, M.top])

  // BLOCK 2: paths. Ideal: straight segments. Remaining: steps — the value holds all day, then drops
  // (H = horizontal line to x, V = vertical line to y), which is what really happens to the work left.
  const ideal = days
    .map((day, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)} ${y(day.ideal).toFixed(1)}`)
    .join(' ')
  const remaining = days
    .flatMap((day, i) =>
      day.remaining == null ? [] : [{ px: x(i).toFixed(1), py: y(day.remaining).toFixed(1) }],
    )
    .map(({ px, py }, i) => (i === 0 ? `M${px} ${py}` : `H${px} V${py}`))
    .join(' ')

  const summary = last
    ? `Burndown of ${data.sprintName}: ${points(last.remaining ?? 0)} of ${points(data.committed)} left on ${dayLabel(last.day)}, ideal ${points(last.ideal)}.`
    : `Burndown of ${data.sprintName}: no days yet.`
  const labelEvery = Math.ceil(days.length / 8)

  return (
    <figure className={styles.figure}>
      <svg viewBox={`0 0 ${String(W)} ${String(H)}`} role="img" aria-label={summary} className={styles.svg}>
        {/* BLOCK 3: gridlines and axes */}
        <g className={styles.grid}>
          {y.ticks(5).map((tick) => (
            <g key={tick}>
              <line x1={M.left} x2={W - M.right} y1={y(tick)} y2={y(tick)} />
              <text x={M.left - 8} y={y(tick)} dy="0.32em" textAnchor="end" className={styles.axisLabel}>
                {tick}
              </text>
            </g>
          ))}
          {days.map((day, i) =>
            i % labelEvery === 0 || i === days.length - 1 ? (
              <text
                key={day.day}
                x={x(i)}
                y={H - M.bottom + 20}
                textAnchor="middle"
                className={styles.axisLabel}
              >
                {dayLabel(day.day)}
              </text>
            ) : null,
          )}
        </g>

        <path d={ideal} className={styles.ideal} />
        {remaining && <path d={remaining} className={styles.remaining} />}

        {/* BLOCK 4: points with a <title> each — the browser shows it on hover, a screen reader can reach it */}
        {days.map((day, i) =>
          day.remaining == null ? null : (
            <circle key={day.day} cx={x(i)} cy={y(day.remaining)} r={4} className={styles.point}>
              <title>{`${dayLabel(day.day)}: ${points(day.remaining)} left (ideal ${String(day.ideal)})`}</title>
            </circle>
          ),
        )}

        {/* scope changes: a marker above the day, with the change spelled out */}
        {days.map((day, i) =>
          day.scopeChange == null ? null : (
            <g key={`scope-${day.day}`} className={styles.scope}>
              <line x1={x(i)} x2={x(i)} y1={M.top} y2={H - M.bottom} />
              <text x={x(i) + 4} y={M.top + 10}>
                {day.scopeChange > 0 ? `+${String(day.scopeChange)}` : String(day.scopeChange)}
              </text>
              <title>{`${dayLabel(day.day)}: scope changed by ${String(day.scopeChange)} points`}</title>
            </g>
          ),
        )}
      </svg>

      <figcaption className={styles.legend}>
        <span className={styles.key} data-series="remaining">
          Remaining
        </span>
        <span className={styles.key} data-series="ideal">
          Ideal
        </span>
        {days.some((day) => day.scopeChange != null) && (
          <span className={styles.key} data-series="scope">
            Scope change
          </span>
        )}
      </figcaption>

      {/* BLOCK 5: the numbers, for anyone who can't (or doesn't want to) read them off a chart */}
      <details className={styles.data}>
        <summary>Show data table</summary>
        <table className={styles.table}>
          <caption className="visually-hidden">Burndown of {data.sprintName}</caption>
          <thead>
            <tr>
              <th scope="col">Day</th>
              <th scope="col">Remaining</th>
              <th scope="col">Ideal</th>
              <th scope="col">Scope change</th>
            </tr>
          </thead>
          <tbody>
            {days.map((day) => (
              <tr key={day.day}>
                <th scope="row">{dayLabel(day.day)}</th>
                <td>{day.remaining ?? '—'}</td>
                <td>{day.ideal}</td>
                <td>{day.scopeChange ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}

import type { VelocityEntry } from '@/shared/api/types.ts'
import { bandScale, linearScale, maxOf } from './scale.ts'
import styles from './Charts.module.css'

const W = 480
const H = 240
const M = { top: 20, right: 12, bottom: 36, left: 36 }

/**
 * Committed vs completed for the last closed sprints, as grouped bars: one band per sprint (bandScale), and inside
 * it a second band scale for the two bars. Committed is neutral, completed is --chart-1; the legend and the value
 * on each bar say which is which, so colour isn't the only clue.
 */
export function Velocity({ sprints }: { sprints: VelocityEntry[] }) {
  const x = bandScale(
    sprints.map((sprint) => sprint.sprintId),
    [M.left, W - M.right],
    0.25,
  )
  const inner = bandScale(['committed', 'completed'] as const, [0, x.bandwidth], 0.1)
  const y = linearScale([0, maxOf(sprints.flatMap((s) => [s.committed, s.completed]))], [H - M.bottom, M.top])
  const average = sprints.length === 0 ? 0 : sprints.reduce((sum, s) => sum + s.completed, 0) / sprints.length
  const summary = `Velocity over ${String(sprints.length)} sprint${sprints.length === 1 ? '' : 's'}: on average ${average.toFixed(1)} points completed.`

  return (
    <figure className={styles.figure}>
      <svg viewBox={`0 0 ${String(W)} ${String(H)}`} role="img" aria-label={summary} className={styles.svg}>
        <g className={styles.grid}>
          {y.ticks(4).map((tick) => (
            <g key={tick}>
              <line x1={M.left} x2={W - M.right} y1={y(tick)} y2={y(tick)} />
              <text x={M.left - 6} y={y(tick)} dy="0.32em" textAnchor="end" className={styles.axisLabel}>
                {tick}
              </text>
            </g>
          ))}
        </g>
        {sprints.map((sprint) => (
          <g key={sprint.sprintId}>
            {(['committed', 'completed'] as const).map((series) => {
              const value = sprint[series]
              const left = x(sprint.sprintId) + inner(series)
              return (
                <g key={series}>
                  <rect
                    x={left}
                    y={y(value)}
                    width={inner.bandwidth}
                    height={y(0) - y(value)}
                    className={styles.bar}
                    data-series={series}
                  >
                    <title>{`${sprint.name}: ${String(value)} points ${series}`}</title>
                  </rect>
                  <text
                    x={left + inner.bandwidth / 2}
                    y={y(value) - 4}
                    textAnchor="middle"
                    className={styles.value}
                  >
                    {value}
                  </text>
                </g>
              )
            })}
            <text
              x={x(sprint.sprintId) + x.bandwidth / 2}
              y={H - M.bottom + 18}
              textAnchor="middle"
              className={styles.axisLabel}
            >
              {sprint.name}
            </text>
          </g>
        ))}
      </svg>
      <figcaption className={styles.legend}>
        <span className={styles.key} data-series="committed">
          Committed
        </span>
        <span className={styles.key} data-series="completed">
          Completed
        </span>
      </figcaption>
      <details className={styles.data}>
        <summary>Show data table</summary>
        <table className={styles.table}>
          <caption className="visually-hidden">Velocity</caption>
          <thead>
            <tr>
              <th scope="col">Sprint</th>
              <th scope="col">Committed</th>
              <th scope="col">Completed</th>
            </tr>
          </thead>
          <tbody>
            {sprints.map((sprint) => (
              <tr key={sprint.sprintId}>
                <th scope="row">{sprint.name}</th>
                <td>{sprint.committed}</td>
                <td>{sprint.completed}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}

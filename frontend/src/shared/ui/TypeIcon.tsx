import { Icon } from './Icon.tsx'
import styles from './TypeIcon.module.css'

/** The issue types the api sends (lowercase, 03-api §5). */
export type IssueType = 'story' | 'bug' | 'task' | 'epic' | 'subtask'

const names: Record<IssueType, string> = {
  story: 'Story',
  bug: 'Bug',
  task: 'Task',
  epic: 'Epic',
  subtask: 'Subtask',
}

/** A small coloured tile with the type's glyph. It has a name, because on a card nothing else says the type. */
export function TypeIcon({ type }: { type: IssueType }) {
  return (
    <span className={styles.root} data-type={type} role="img" aria-label={names[type]} title={names[type]}>
      {/* the glyph a size smaller than the tile, drawn heavier so it reads at 12px */}
      <Icon name={`type-${type}`} size="xs" strokeWidth={3} />
    </span>
  )
}

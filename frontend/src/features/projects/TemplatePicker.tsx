import type { Template } from '@/shared/api/types.ts'
import { Icon } from '@/shared/ui/Icon.tsx'
import type { IconName } from '@/shared/ui/icons.ts'
import styles from './TemplatePicker.module.css'

export const templates: readonly { value: Template; name: string; icon: IconName; description: string }[] = [
  {
    value: 'scrum',
    name: 'Scrum',
    icon: 'board',
    description: 'Sprints, a backlog and a board: To do → In progress → In review → Done.',
  },
  {
    value: 'kanban',
    name: 'Kanban',
    icon: 'dashboard',
    description: 'Continuous flow with a WIP limit: Backlog → Selected → In progress → Done.',
  },
  {
    value: 'bugs',
    name: 'Bug tracking',
    icon: 'type-bug',
    description: 'Triage → Confirmed → Fixing → Verifying → Closed.',
  },
  { value: 'blank', name: 'Blank', icon: 'plus', description: 'To do → In progress → Done; shape it later.' },
]

/**
 * The template as large radio cards: a fieldset of native radios, so it's one Tab stop, arrows move the
 * choice, and the form submits `template` without any code. The card styles itself from :has(:checked).
 */
export function TemplatePicker({
  value,
  onChange,
}: {
  value: Template
  onChange: (value: Template) => void
}) {
  return (
    <fieldset className={styles.root}>
      <legend className={styles.legend}>Template</legend>
      <div className={styles.cards}>
        {templates.map((template) => (
          <label key={template.value} className={styles.card}>
            <input
              type="radio"
              name="template"
              value={template.value}
              checked={value === template.value}
              onChange={() => {
                onChange(template.value)
              }}
              className={styles.input}
            />
            <span className={styles.icon}>
              <Icon name={template.icon} />
            </span>
            <span className={styles.name}>{template.name}</span>
            <span className={styles.description}>{template.description}</span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}

import { useId } from 'react'
import { Icon } from './Icon.tsx'
import type { IconName } from './icons.ts'
import styles from './Segmented.module.css'

export interface SegmentedOption<T extends string> {
  value: T
  label: string
  icon?: IconName
  /** Show only the icon; the label is still the accessible name. */
  iconOnly?: boolean
}

interface SegmentedProps<T extends string> {
  /** The group's name for screen readers ("View", "Mode"). */
  label: string
  value: T
  options: readonly SegmentedOption<T>[]
  onChange: (value: T) => void
}

/**
 * One choice out of a few, side by side. Underneath: native radio buttons in a fieldset, so arrow keys move
 * between options and a screen reader announces "radio, 2 of 3" with no ARIA of ours.
 */
export function Segmented<T extends string>({ label, value, options, onChange }: SegmentedProps<T>) {
  const name = useId()
  return (
    <fieldset className={styles.root}>
      <legend className="visually-hidden">{label}</legend>
      {options.map((option) => (
        <label key={option.value} className={styles.option}>
          <input
            type="radio"
            name={name}
            value={option.value}
            checked={option.value === value}
            onChange={() => {
              onChange(option.value)
            }}
            className={styles.input}
          />
          {option.icon && <Icon name={option.icon} size="sm" />}
          <span className={option.iconOnly ? 'visually-hidden' : undefined}>{option.label}</span>
        </label>
      ))}
    </fieldset>
  )
}

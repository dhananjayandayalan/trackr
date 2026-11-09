import type { ComponentPropsWithRef } from 'react'
import { Icon } from './Icon.tsx'
import inputStyles from './TextInput.module.css'
import styles from './Select.module.css'

/** A native <select>: keyboard, screen reader and mobile pickers for free; only the arrow is ours. */
export function Select({ className, ...rest }: ComponentPropsWithRef<'select'>) {
  return (
    <span className={styles.root}>
      <select
        className={
          className
            ? `${inputStyles.control} ${styles.select} ${className}`
            : `${inputStyles.control} ${styles.select}`
        }
        {...rest}
      />
      <Icon name="chevron-down" size="sm" className={styles.arrow} />
    </span>
  )
}

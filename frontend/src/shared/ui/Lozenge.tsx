import type { ComponentPropsWithRef } from 'react'
import styles from './Lozenge.module.css'

/** The status colours of 08-css-volt §2; a workflow status maps to one by its colour token. */
export type LozengeTone = 'todo' | 'progress' | 'review' | 'done' | 'blocked'

type LozengeProps = ComponentPropsWithRef<'span'> & {
  tone?: LozengeTone
}

/** A status label, e.g. IN REVIEW. Text, not a control: the colour repeats what the words say. */
export function Lozenge({ tone = 'todo', className, ...rest }: LozengeProps) {
  return (
    <span data-tone={tone} className={className ? `${styles.root} ${className}` : styles.root} {...rest} />
  )
}

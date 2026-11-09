import type { ComponentPropsWithRef } from 'react'
import styles from './Kbd.module.css'

/** A key or shortcut the user presses, e.g. <Kbd>/</Kbd>. <kbd> is the HTML element made for it. */
export function Kbd({ className, ...rest }: ComponentPropsWithRef<'kbd'>) {
  return <kbd className={className ? `${styles.root} ${className}` : styles.root} {...rest} />
}

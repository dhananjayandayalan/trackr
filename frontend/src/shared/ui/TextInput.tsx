import type { ComponentPropsWithRef } from 'react'
import styles from './TextInput.module.css'

/** A native <input>, VOLT-styled. Everything else (type, required, autoComplete…) is the platform's. */
export function TextInput({ className, ...rest }: ComponentPropsWithRef<'input'>) {
  return <input className={className ? `${styles.control} ${className}` : styles.control} {...rest} />
}

/** A native <textarea> that grows with its content (field-sizing: content, where supported). */
export function Textarea({ className, ...rest }: ComponentPropsWithRef<'textarea'>) {
  return (
    <textarea
      className={
        className ? `${styles.control} ${styles.area} ${className}` : `${styles.control} ${styles.area}`
      }
      {...rest}
    />
  )
}

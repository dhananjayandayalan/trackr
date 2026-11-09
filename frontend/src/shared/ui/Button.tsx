import type { ComponentPropsWithRef } from 'react'
import { Icon } from './Icon.tsx'
import type { IconName } from './icons.ts'
import styles from './Button.module.css'

// BLOCK 1: props — every native <button> prop (onClick, disabled, aria-*, and in React 19 `ref` too: refs
// are ordinary props now, no forwardRef), plus the VOLT ones
export type ButtonProps = ComponentPropsWithRef<'button'> & {
  variant?: 'primary' | 'accent' | 'secondary' | 'ghost' | 'danger'
  size?: 'sm' | 'md' | 'lg'
  /** A leading icon; with no children the button is a square icon button and needs an aria-label. */
  icon?: IconName
}

// BLOCK 2: component — variant and size go out as data attributes; the CSS picks them up, so there is
// one class and no string concatenation of modifiers
export function Button({
  variant = 'secondary',
  size = 'md',
  icon,
  type = 'button',
  className,
  children,
  ...rest
}: ButtonProps) {
  const iconOnly = icon !== undefined && (children === undefined || children === null)
  return (
    <button
      // type="button" by default: a plain <button> inside a <form> would submit it
      type={type}
      data-variant={variant}
      data-size={size}
      data-icon-only={iconOnly || undefined}
      className={className ? `${styles.root} ${className}` : styles.root}
      {...rest}
    >
      {/* BLOCK 3: icon slot — decorative, the button's text (or aria-label) is its name */}
      {icon && <Icon name={icon} size={size === 'sm' ? 'sm' : 'md'} />}
      {children}
    </button>
  )
}

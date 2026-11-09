import type { ComponentPropsWithRef } from 'react'
import { icons, type IconName } from './icons.ts'
import styles from './Icon.module.css'

// BLOCK 1: props — any <svg> attribute, plus which icon and an accessible name
type IconProps = Omit<ComponentPropsWithRef<'svg'>, 'children'> & {
  name: IconName
  /** Spoken name for an icon that means something on its own. Without it the icon is decorative and hidden. */
  label?: string
  size?: 'xs' | 'sm' | 'md' | 'lg'
}

// BLOCK 2: inline SVG — no sprite request, and the paths inherit `color` through currentColor
export function Icon({ name, label, size = 'md', className, ...rest }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="square"
      strokeLinejoin="miter"
      className={className ? `${styles.root} ${className}` : styles.root}
      data-size={size}
      // decorative icons are invisible to screen readers; meaningful ones get role img and a name
      {...(label ? { role: 'img', 'aria-label': label } : { 'aria-hidden': true })}
      focusable="false"
      {...rest}
    >
      {icons[name].map((d) => (
        <path key={d} d={d} />
      ))}
    </svg>
  )
}

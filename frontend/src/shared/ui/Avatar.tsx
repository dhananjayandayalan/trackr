import styles from './Avatar.module.css'

/** The avatar colours the api assigns (app_user.avatar_color). */
export type AvatarColor = 'pink' | 'cyan' | 'lime' | 'yellow' | 'orange' | 'violet'

interface AvatarProps {
  name: string
  initials: string
  color: AvatarColor
  size?: 'sm' | 'md' | 'lg'
  /** The name is written right next to it: hide the avatar from assistive tech, or the name is read twice. */
  decorative?: boolean
}

/**
 * Initials on the person's colour. The full name is the accessible name (and the tooltip). The initials are drawn
 * by CSS (`content: attr(data-initials)`), not a text node: an avatar sits inside buttons ("Account", an
 * assignee picker) whose names must contain their visible text (WCAG 2.5.3), and "AL" next to "Ada Lovelace"
 * would be text no screen reader user needs. The image's name already says who it is.
 */
export function Avatar({ name, initials, color, size = 'md', decorative = false }: AvatarProps) {
  return (
    <span
      className={styles.root}
      data-color={color}
      data-size={size}
      data-initials={initials}
      {...(decorative ? { 'aria-hidden': true } : { role: 'img', 'aria-label': name, title: name })}
    />
  )
}

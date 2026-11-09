import styles from './Avatar.module.css'

/** The avatar colours the api assigns (app_user.avatar_color). */
export type AvatarColor = 'pink' | 'cyan' | 'lime' | 'yellow' | 'orange' | 'violet'

interface AvatarProps {
  name: string
  initials: string
  color: AvatarColor
  size?: 'sm' | 'md' | 'lg'
}

/** Initials on the person's colour. The full name is the accessible name (and the tooltip). */
export function Avatar({ name, initials, color, size = 'md' }: AvatarProps) {
  return (
    <span
      className={styles.root}
      data-color={color}
      data-size={size}
      role="img"
      aria-label={name}
      title={name}
    >
      {initials}
    </span>
  )
}

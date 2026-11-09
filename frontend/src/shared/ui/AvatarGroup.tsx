import { Avatar, type AvatarColor } from './Avatar.tsx'
import styles from './AvatarGroup.module.css'

export interface Person {
  id: string
  displayName: string
  initials: string
  avatarColor: string
}

const colors: readonly string[] = ['pink', 'cyan', 'lime', 'yellow', 'orange', 'violet']

/** The api sends the colour as a string; anything unexpected falls back to violet instead of breaking. */
export function avatarColor(color: string): AvatarColor {
  return colors.includes(color) ? (color as AvatarColor) : 'violet'
}

/** Up to `max` overlapping avatars, then "+N" for the rest. The list is named for screen readers. */
export function AvatarGroup({
  people,
  max = 3,
  size = 'sm',
}: {
  people: readonly Person[]
  max?: number
  size?: 'sm' | 'md'
}) {
  const shown = people.slice(0, max)
  const rest = people.length - shown.length
  return (
    <ul className={styles.root} aria-label={`${people.length} people`}>
      {shown.map((person) => (
        <li key={person.id} className={styles.item}>
          <Avatar
            name={person.displayName}
            initials={person.initials}
            color={avatarColor(person.avatarColor)}
            size={size}
          />
        </li>
      ))}
      {rest > 0 && (
        <li className={styles.more} data-size={size}>
          +{rest}
        </li>
      )}
    </ul>
  )
}

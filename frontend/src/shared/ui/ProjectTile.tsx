import styles from './ProjectTile.module.css'

/** A project's key on its colour — the project's "avatar" on cards, lists and the sidebar. */
export function ProjectTile({
  projectKey,
  color,
  size = 'md',
}: {
  projectKey: string
  color: string
  size?: 'sm' | 'md' | 'lg'
}) {
  return (
    <span className={styles.root} data-color={color} data-size={size} aria-hidden="true">
      {projectKey}
    </span>
  )
}

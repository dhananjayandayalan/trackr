import { Link } from '@tanstack/react-router'
import type { ProjectSummary, Template } from '@/shared/api/types.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { Button } from '@/shared/ui/Button.tsx'
import { Progress } from '@/shared/ui/Progress.tsx'
import { ProjectTile } from '@/shared/ui/ProjectTile.tsx'
import { useStarProject } from './mutations.ts'
import styles from './ProjectCard.module.css'

export const templateNames: Record<Template, string> = {
  scrum: 'Scrum',
  kanban: 'Kanban',
  bugs: 'Bug tracking',
  blank: 'Blank',
}

/** One project in the grid. The name is the link; the star is a separate button beside it, not inside it. */
export function ProjectCard({ project }: { project: ProjectSummary }) {
  return (
    <article className={styles.root}>
      <header className={styles.header}>
        <ProjectTile projectKey={project.key} color={project.color} size="lg" />
        <div className={styles.title}>
          <h2 className={styles.name}>
            {/* the link's ::after covers the whole card, so the card is clickable but stays one link */}
            <Link to="/p/$projectKey" params={{ projectKey: project.key }} className={styles.link}>
              {project.name}
            </Link>
          </h2>
          <p className={styles.meta}>
            {project.key} · {templateNames[project.template]}
          </p>
        </div>
        <StarButton project={project} />
      </header>

      <p className={styles.sprint}>{project.activeSprint ?? 'No active sprint'}</p>
      <Progress value={project.progressPct} label={`${project.name}: issues done`} />

      <footer className={styles.footer}>
        <span className={styles.lead}>
          <Avatar
            name={`Lead: ${project.lead.displayName}`}
            initials={project.lead.initials}
            color={avatarColor(project.lead.avatarColor)}
            size="sm"
          />
          {project.lead.displayName}
        </span>
        <span className={styles.open}>
          <strong>{project.openIssues}</strong> open · {project.progressPct}% done
        </span>
      </footer>
    </article>
  )
}

/** A toggle button: aria-pressed says whether it's on, the label says what it is. Optimistic (mutations.ts). */
export function StarButton({ project }: { project: ProjectSummary }) {
  const star = useStarProject()
  return (
    <Button
      variant="ghost"
      size="sm"
      icon="star"
      aria-label={`Star ${project.name}`}
      aria-pressed={project.starred}
      className={styles.star}
      onClick={() => {
        star.mutate({ key: project.key, starred: !project.starred })
      }}
    />
  )
}

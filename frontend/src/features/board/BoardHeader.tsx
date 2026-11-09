import { useState } from 'react'
import { CompleteSprintDialog } from '@/features/backlog/CompleteSprintDialog.tsx'
import type { ProjectDetails } from '@/shared/api/types.ts'
import { dayLabel, daysUntil } from '@/shared/lib/dates.ts'
import { Button } from '@/shared/ui/Button.tsx'
import type { LiveBoard } from './board-cache.ts'
import { LiveIndicator } from './LiveIndicator.tsx'
import styles from './BoardHeader.module.css'

/** The board's title row: which sprint, its goal and dates, who's here, and "Complete sprint". */
export function BoardHeader({ project, board }: { project: ProjectDetails; board: LiveBoard }) {
  const [completing, setCompleting] = useState(false)
  const sprint = board.sprint
  const people = project.members.map((member) => ({ ...member, id: member.userId }))
  const canComplete = sprint?.state === 'active' && project.myRole !== 'viewer'

  return (
    <header className={styles.root}>
      <div className={styles.text}>
        <p className={styles.eyebrow}>{project.name} · Board</p>
        <h1 className={styles.title}>
          {sprint?.name ?? (board.board.type === 'kanban' ? board.board.name : 'Board')}
        </h1>
        {sprint?.goal && <p className={styles.goal}>{sprint.goal}</p>}
        {sprint?.startsOn && sprint.endsOn && (
          <p className={styles.dates}>
            {dayLabel(sprint.startsOn)} – {dayLabel(sprint.endsOn)} · <DaysLeft endsOn={sprint.endsOn} />
          </p>
        )}
      </div>
      <div className={styles.actions}>
        <LiveIndicator people={people} />
        {canComplete && (
          <Button
            variant="primary"
            onClick={() => {
              setCompleting(true)
            }}
          >
            Complete sprint
          </Button>
        )}
      </div>
      {sprint && canComplete && (
        <CompleteSprintDialog
          open={completing}
          onClose={() => {
            setCompleting(false)
          }}
          projectKey={project.key}
          sprint={{ id: sprint.id, name: sprint.name }}
        />
      )}
    </header>
  )
}

function DaysLeft({ endsOn }: { endsOn: string }) {
  const days = daysUntil(endsOn)
  if (days < 0)
    return (
      <span className={styles.overdue}>
        ended {-days} {-days === 1 ? 'day' : 'days'} ago
      </span>
    )
  if (days === 0) return <span>ends today</span>
  return (
    <span>
      {days} {days === 1 ? 'day' : 'days'} left
    </span>
  )
}

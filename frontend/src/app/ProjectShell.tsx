import { useSuspenseQuery } from '@tanstack/react-query'
import { Outlet, getRouteApi } from '@tanstack/react-router'
import { useProjectLive } from '@/features/board/live.ts'
import { ProjectLiveContext } from '@/features/board/LiveIndicator.tsx'
import { projectQuery } from '@/features/projects/queries.ts'
import { AppShell } from './AppShell.tsx'
import { Sidebar } from './Sidebar.tsx'

const projectRoute = getRouteApi('/_authed/p/$projectKey')

/**
 * /p/$projectKey/…: the shell with the project's sidebar, and the project's live stream. The stream belongs
 * here, not to a page: board, backlog and the issue screens all show the same project, so switching between
 * them keeps one connection (and its Last-Event-ID) instead of dropping and reopening it on every click.
 * The pages read its status and viewers from ProjectLiveContext.
 */
export function ProjectShell() {
  const { projectKey } = projectRoute.useParams()
  const { data: project } = useSuspenseQuery(projectQuery(projectKey))
  const live = useProjectLive(projectKey)
  return (
    <ProjectLiveContext value={live}>
      <AppShell sidebar={<Sidebar project={project} />}>
        <Outlet />
      </AppShell>
    </ProjectLiveContext>
  )
}

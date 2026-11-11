import { useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from '@tanstack/react-router'
import { projectKeys } from '@/features/projects/queries.ts'
import type { ProjectSummary } from '@/shared/api/types.ts'
import { useShortcut } from './keyboard.ts'

/**
 * The g-sequences of 00-product §3.13: `g b` board, `g k` backlog. Inside a project they go to that project's
 * screen; elsewhere to the project visited last (from the cached project list), if there is one. Renders nothing.
 */
export function GlobalShortcuts() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { projectKey } = useParams({ strict: false })

  const target = () => {
    if (projectKey) {
      return projectKey
    }
    const projects = queryClient.getQueryData<ProjectSummary[]>(projectKeys.list()) ?? []
    return projects
      .filter((project) => project.lastVisitedAt)
      .sort((a, b) => (b.lastVisitedAt ?? '').localeCompare(a.lastVisitedAt ?? ''))[0]?.key
  }

  useShortcut('g b', () => {
    const key = target()
    if (key) {
      void navigate({ to: '/p/$projectKey/board', params: { projectKey: key } })
    }
  })
  useShortcut('g k', () => {
    const key = target()
    if (key) {
      void navigate({ to: '/p/$projectKey/backlog', params: { projectKey: key } })
    }
  })
  return null
}

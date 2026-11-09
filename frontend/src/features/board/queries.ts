import { queryOptions } from '@tanstack/react-query'
import { api, unwrap } from '@/shared/api/client.ts'
import type { LiveBoard } from './board-cache.ts'

// BLOCK 1: keys. ['board', projectKey] is the prefix live updates patch and invalidate: every view of one
// project's board (active sprint, a given sprint, the "recent" variant) sits under it.
export const boardKeys = {
  all: ['board'] as const,
  project: (projectKey: string) => [...boardKeys.all, projectKey] as const,
  view: (projectKey: string, sprint: string, recent: boolean) =>
    [...boardKeys.project(projectKey), sprint, { recent }] as const,
}

/**
 * The board. Only `recent` goes to the api — it needs updatedAt, which cards don't carry. Every other
 * filter (mine, bugs, assignees, text) runs in the browser over the cached board: instant, one cache entry
 * to keep live, and the column counts stay the column's real counts.
 */
export function boardQuery(projectKey: string, sprint = 'active', recent = false) {
  return queryOptions({
    queryKey: boardKeys.view(projectKey, sprint, recent),
    queryFn: async (): Promise<LiveBoard> =>
      unwrap(
        api.GET('/api/v1/projects/{key}/board', {
          params: { path: { key: projectKey }, query: { sprint, ...(recent ? { recent } : {}) } },
        }),
      ),
  })
}

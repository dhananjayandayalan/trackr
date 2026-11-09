import { queryOptions } from '@tanstack/react-query'
import { api, unwrap } from '@/shared/api/client.ts'
import type { LiveBacklog } from './backlog-cache.ts'

export const backlogKeys = {
  all: ['backlog'] as const,
  project: (projectKey: string) => [...backlogKeys.all, projectKey] as const,
}

/**
 * The whole backlog, unfiltered: the epic, type and text filters run in the browser (1 000 rows filter in
 * well under a millisecond), so live updates patch one cache entry and every filter change is instant.
 */
export function backlogQuery(projectKey: string) {
  return queryOptions({
    queryKey: backlogKeys.project(projectKey),
    queryFn: async (): Promise<LiveBacklog> =>
      unwrap(api.GET('/api/v1/projects/{key}/backlog', { params: { path: { key: projectKey } } })),
  })
}

import { queryOptions } from '@tanstack/react-query'
import { api, unwrap } from '@/shared/api/client.ts'
import { isApiError } from '@/shared/api/errors.ts'

// ['reports', projectKey, …]: a sprint event on the project's live stream invalidates the whole prefix
export const reportKeys = {
  project: (projectKey: string) => ['reports', projectKey] as const,
  burndown: (projectKey: string, sprintId: string | undefined) =>
    [...reportKeys.project(projectKey), 'burndown', sprintId ?? 'current'] as const,
  velocity: (projectKey: string) => [...reportKeys.project(projectKey), 'velocity'] as const,
  sprint: (projectKey: string, sprintId: string | undefined) =>
    [...reportKeys.project(projectKey), 'sprint', sprintId ?? 'current'] as const,
  breakdown: (projectKey: string, sprintId: string | undefined) =>
    [...reportKeys.project(projectKey), 'breakdown', sprintId ?? 'current'] as const,
}

// a 404 ("no sprint to report on", a Kanban project) is an answer, not a failure: don't retry it
const noRetryOn404 = (count: number, error: Error) =>
  !(isApiError(error) && error.status === 404) && count < 2
const sprintParam = (sprintId: string | undefined) => (sprintId ? { sprintId } : {})

export function burndownQuery(projectKey: string, sprintId?: string) {
  return queryOptions({
    queryKey: reportKeys.burndown(projectKey, sprintId),
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/projects/{key}/reports/burndown', {
          params: { path: { key: projectKey }, query: sprintParam(sprintId) },
        }),
      ),
    retry: noRetryOn404,
  })
}

export function velocityQuery(projectKey: string) {
  return queryOptions({
    queryKey: reportKeys.velocity(projectKey),
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/projects/{key}/reports/velocity', {
          params: { path: { key: projectKey }, query: { last: 5 } },
        }),
      ),
  })
}

export function sprintReportQuery(projectKey: string, sprintId?: string) {
  return queryOptions({
    queryKey: reportKeys.sprint(projectKey, sprintId),
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/projects/{key}/reports/sprint', {
          params: { path: { key: projectKey }, query: sprintParam(sprintId) },
        }),
      ),
    retry: noRetryOn404,
  })
}

export function breakdownQuery(projectKey: string, sprintId?: string) {
  return queryOptions({
    queryKey: reportKeys.breakdown(projectKey, sprintId),
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/projects/{key}/reports/breakdown', {
          params: { path: { key: projectKey }, query: sprintParam(sprintId) },
        }),
      ),
  })
}

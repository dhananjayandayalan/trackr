import { useMutation, useQueryClient, queryOptions } from '@tanstack/react-query'
import { issueKeys } from '@/features/issue/queries.ts'
import { api, unwrap } from '@/shared/api/client.ts'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import type { IssuePatch, TimelineView } from '@/shared/api/types.ts'
import { useToast } from '@/shared/ui/Toast.tsx'

export const timelineKeys = {
  project: (projectKey: string) => ['timeline', projectKey] as const,
}

export function timelineQuery(projectKey: string) {
  return queryOptions({
    queryKey: timelineKeys.project(projectKey),
    queryFn: () =>
      unwrap(api.GET('/api/v1/projects/{key}/timeline', { params: { path: { key: projectKey } } })),
  })
}

/** Change one row's dates in the cached timeline (an epic, or a child of one). */
function withDates(
  view: TimelineView,
  key: string,
  dates: { startDate?: string; dueDate?: string },
  version?: number,
) {
  return {
    epics: view.epics.map((epic) =>
      epic.key === key
        ? { ...epic, ...dates, ...(version === undefined ? {} : { version }) }
        : {
            ...epic,
            children: epic.children.map((child) =>
              child.key === key
                ? { ...child, ...dates, ...(version === undefined ? {} : { version }) }
                : child,
            ),
          },
    ),
  }
}

/**
 * Dragging a bar's end: an optimistic PATCH (03-api §3.9). The bar stays where it was dropped at once; the
 * server's answer brings the new version; a 412 or any failure puts it back and says why.
 */
export function useMoveDates(projectKey: string) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const key = timelineKeys.project(projectKey)
  return useMutation({
    mutationFn: ({
      issueKey,
      version,
      dates,
    }: {
      issueKey: string
      version: number
      dates: { dueDate?: string; startDate?: string }
    }) =>
      unwrap(
        api.PATCH('/api/v1/issues/{key}', {
          params: { path: { key: issueKey }, header: { 'If-Match': `"v${String(version)}"` } },
          // a merge patch is any subset of the fields (IssuePatch); the generated type lists them all
          body: dates as Required<IssuePatch>,
          headers: { 'Content-Type': 'application/merge-patch+json' },
        }),
      ),
    onMutate: async ({ issueKey, dates }) => {
      await queryClient.cancelQueries({ queryKey: key })
      const previous = queryClient.getQueryData<TimelineView>(key)
      if (previous) {
        queryClient.setQueryData(key, withDates(previous, issueKey, dates))
      }
      return { previous }
    },
    onError: (error, _vars, context) => {
      queryClient.setQueryData(key, context?.previous)
      toast(
        isApiError(error) && error.status === 412
          ? 'Someone changed this issue meanwhile. The timeline is reloaded — drag again.'
          : `Couldn't move the date: ${messageOf(error)}`,
        'error',
      )
      void queryClient.invalidateQueries({ queryKey: key })
    },
    onSuccess: (issue) => {
      queryClient.setQueryData<TimelineView>(
        key,
        (view) =>
          view &&
          withDates(
            view,
            issue.key,
            {
              ...(issue.startDate ? { startDate: issue.startDate } : {}),
              ...(issue.dueDate ? { dueDate: issue.dueDate } : {}),
            },
            issue.version,
          ),
      )
      queryClient.setQueryData(issueKeys.detail(issue.key), issue)
    },
  })
}

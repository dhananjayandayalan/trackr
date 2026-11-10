import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query'
import { api, unwrap } from '@/shared/api/client.ts'

// BLOCK 1: keys. Everything about one issue sits under ['issue', key]; each piece has its own key, so a new
// comment refetches the comments and nothing else. Invalidate with `exact: true` unless you mean all of it.
export const issueKeys = {
  all: ['issue'] as const,
  detail: (key: string) => [...issueKeys.all, key] as const,
  transitions: (key: string) => [...issueKeys.detail(key), 'transitions'] as const,
  comments: (key: string) => [...issueKeys.detail(key), 'comments'] as const,
  history: (key: string) => [...issueKeys.detail(key), 'history'] as const,
  worklogs: (key: string) => [...issueKeys.detail(key), 'worklogs'] as const,
}

/** The whole issue page in one request (03-api §3.4); it also records the view for "Recently viewed". */
export function issueQuery(key: string) {
  return queryOptions({
    queryKey: issueKeys.detail(key),
    queryFn: () => unwrap(api.GET('/api/v1/issues/{key}', { params: { path: { key } } })),
  })
}

/**
 * The statuses this issue may move to from where it is now. Prefetched with the issue, so the status menu
 * opens with its items already there. Its key sits under the issue's: whenever the issue is refetched
 * (it changed status), refetch this too.
 */
export function transitionsQuery(key: string) {
  return queryOptions({
    queryKey: issueKeys.transitions(key),
    queryFn: () => unwrap(api.GET('/api/v1/issues/{key}/transitions', { params: { path: { key } } })),
  })
}

// BLOCK 2: the Activity tabs. Comments and history are cursor-paged (newest first): an infinite query keeps
// the pages loaded so far, and "Show older" fetches the next with the last page's cursor.
export function commentsQuery(key: string) {
  return infiniteQueryOptions({
    queryKey: issueKeys.comments(key),
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/issues/{key}/comments', {
          params: { path: { key }, query: pageParam ? { cursor: pageParam } : {} },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
}

export function historyQuery(key: string) {
  return infiniteQueryOptions({
    queryKey: issueKeys.history(key),
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/issues/{key}/history', {
          params: { path: { key }, query: pageParam ? { cursor: pageParam } : {} },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
}

export function worklogsQuery(key: string) {
  return queryOptions({
    queryKey: issueKeys.worklogs(key),
    queryFn: () => unwrap(api.GET('/api/v1/issues/{key}/worklogs', { params: { path: { key } } })),
  })
}

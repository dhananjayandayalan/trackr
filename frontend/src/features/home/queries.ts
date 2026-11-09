import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query'
import { api, unwrap } from '@/shared/api/client.ts'

/** The four tabs of "Your work" (00-product §3.2); also the values of the ?tab= search param. */
export const workTabs = ['assigned', 'worked', 'viewed', 'starred'] as const
export type WorkTab = (typeof workTabs)[number]

export const homeKeys = {
  all: ['home'] as const,
  stats: () => [...homeKeys.all, 'stats'] as const,
  work: (tab: WorkTab) => [...homeKeys.all, 'work', tab] as const,
  activity: () => [...homeKeys.all, 'activity'] as const,
}

export function statsQuery() {
  return queryOptions({
    queryKey: homeKeys.stats(),
    queryFn: () => unwrap(api.GET('/api/v1/me/stats')),
  })
}

// A cursor-paged list: each page's nextCursor is the next page's param; null ends the list.
export function workQuery(tab: WorkTab) {
  return infiniteQueryOptions({
    queryKey: homeKeys.work(tab),
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/me/work', {
          params: { query: { tab, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor ?? null,
  })
}

export function activityQuery() {
  return queryOptions({
    queryKey: homeKeys.activity(),
    queryFn: () => unwrap(api.GET('/api/v1/me/activity', { params: { query: { limit: 20 } } })),
  })
}

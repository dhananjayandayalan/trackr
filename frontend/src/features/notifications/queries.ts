import {
  infiniteQueryOptions,
  queryOptions,
  useMutation,
  useQueryClient,
  type InfiniteData,
  type QueryClient,
} from '@tanstack/react-query'
import { useEffect } from 'react'
import { z } from 'zod'
import { api, unwrap } from '@/shared/api/client.ts'
import { openStream } from '@/shared/api/sse.ts'
import type { NotificationPage, NotificationView } from '@/shared/api/types.ts'

export const notificationKeys = {
  all: ['notifications'] as const,
  list: () => [...notificationKeys.all, 'list'] as const,
  unread: () => [...notificationKeys.all, 'unread-count'] as const,
}

// BLOCK 1: reads — the list is cursor-paged, the badge is its own tiny request
export function notificationsQuery() {
  return infiniteQueryOptions({
    queryKey: notificationKeys.list(),
    queryFn: ({ pageParam }) =>
      unwrap(api.GET('/api/v1/notifications', { params: { query: pageParam ? { cursor: pageParam } : {} } })),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor ?? null,
  })
}

export function unreadCountQuery() {
  return queryOptions({
    queryKey: notificationKeys.unread(),
    queryFn: async () => (await unwrap(api.GET('/api/v1/notifications/unread-count'))).count,
  })
}

type Pages = InfiniteData<NotificationPage, string | null>

function markInCache(queryClient: QueryClient, ids: ReadonlySet<string> | 'all', at: string) {
  queryClient.setQueryData<Pages>(notificationKeys.list(), (data) =>
    data
      ? {
          ...data,
          pages: data.pages.map((page) => ({
            ...page,
            items: page.items.map((item) =>
              item.readAt == null && (ids === 'all' || ids.has(item.id)) ? { ...item, readAt: at } : item,
            ),
          })),
        }
      : data,
  )
}

// BLOCK 2: mark read — optimistic: the dot goes at once; the server answers with the new badge number
export function useMarkRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ids: string[] | 'all') =>
      unwrap(api.POST('/api/v1/notifications/read', { body: ids === 'all' ? { all: true } : { ids } })),
    onMutate: (ids) => {
      markInCache(queryClient, ids === 'all' ? 'all' : new Set(ids), new Date().toISOString())
    },
    onSuccess: ({ count }) => {
      queryClient.setQueryData(notificationKeys.unread(), count)
    },
    onError: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  })
}

// BLOCK 3: the live stream (/me/stream, 03-api §4): a new notification goes on top of the list, the badge takes
// the server's count. `ready` again after a reconnect means something may have been missed: refetch.
const notificationEvent = z.object({
  notificationId: z.string(),
  kind: z.string(),
  issueKey: z.string(),
  summary: z.string(),
  actorName: z.string(),
  occurredAt: z.string().optional(),
})
const countEvent = z.object({ count: z.number() })

export function useMeStream() {
  const queryClient = useQueryClient()
  useEffect(() => {
    let ready = false
    return openStream('/api/v1/me/stream', {
      ready: () => {
        if (ready) {
          void queryClient.invalidateQueries({ queryKey: notificationKeys.all })
        }
        ready = true
      },
      notification: (data) => {
        const event = notificationEvent.safeParse(data)
        if (!event.success) {
          return
        }
        const item: NotificationView = {
          id: event.data.notificationId,
          kind: event.data.kind,
          issueKey: event.data.issueKey,
          summary: event.data.summary,
          actorName: event.data.actorName,
          createdAt: event.data.occurredAt ?? new Date().toISOString(),
          readAt: null,
        }
        queryClient.setQueryData<Pages>(notificationKeys.list(), (list) => {
          if (!list) {
            return list
          }
          const [first, ...rest] = list.pages
          return first ? { ...list, pages: [{ ...first, items: [item, ...first.items] }, ...rest] } : list
        })
      },
      'unread-count': (data) => {
        const event = countEvent.safeParse(data)
        if (event.success) {
          queryClient.setQueryData(notificationKeys.unread(), event.data.count)
        }
      },
    })
  }, [queryClient])
}

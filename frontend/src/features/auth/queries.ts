import { queryOptions } from '@tanstack/react-query'
import { api, unwrap } from '@/shared/api/client.ts'

// BLOCK 1: the key factory. Every query key of the feature comes from here, so invalidating "everything
// about me" is one call with a prefix, and a typo can't create a second, never-invalidated cache entry.
export const meKeys = {
  me: ['me'] as const,
}

// BLOCK 2: query options — key + function + settings in one object, used by the route's beforeLoad
// (ensureQueryData) and by components (useSuspenseQuery) alike, so both always mean the same query.
// Who I am changes only by signing in or out, which set or clear the cache themselves: no refetching.
export function meQuery() {
  return queryOptions({
    queryKey: meKeys.me,
    queryFn: () => unwrap(api.GET('/api/v1/me')),
    staleTime: Infinity,
  })
}

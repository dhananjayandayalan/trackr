import type { DataTag, QueryClient, QueryKey } from '@tanstack/react-query'

/**
 * The cached data for a key — the test fails clearly if there is none. A key from queryOptions() is tagged
 * with its data type (DataTag), so the result is typed without a cast.
 */
export function cached<T>(queryClient: QueryClient, queryKey: DataTag<QueryKey, T, unknown>): T {
  const data = queryClient.getQueryData(queryKey)
  if (data === undefined) {
    throw new Error(`nothing cached for ${JSON.stringify(queryKey)}`)
  }
  return data
}

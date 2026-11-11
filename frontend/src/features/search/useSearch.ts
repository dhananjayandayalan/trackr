import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query'
import { isApiError } from '@/shared/api/errors.ts'
import type { SearchHit } from '@/shared/api/types.ts'
import { searchQuery } from './queries.ts'

export interface TqlError {
  message: string
  /** offset in the query where the parser gave up; the editor underlines from there */
  position: number
  expected: string[]
}

/**
 * The results for a query: every loaded page as one list, plus what the table needs to load more. While a new
 * query runs, the previous results stay on screen (keepPreviousData) instead of flashing to a spinner; a TQL
 * error comes back as data for the editor, not as a crash.
 */
export function useSearch(tql: string) {
  const query = useInfiniteQuery({ ...searchQuery(tql), placeholderData: keepPreviousData })
  const rows: SearchHit[] = query.data?.pages.flatMap((page) => page.items) ?? []
  const first = query.data?.pages[0]
  const error = query.error
  const tqlError: TqlError | null =
    isApiError(error) && error.status === 400
      ? {
          message: error.message,
          position: error.problem.position ?? 0,
          expected: error.problem.expected ?? [],
        }
      : null

  return {
    rows,
    total: first?.total ?? 0,
    tookMs: first?.tookMs ?? 0,
    isLoading: query.isPending,
    // placeholder data = the old query's results shown while the new one loads
    isStale: query.isPlaceholderData,
    tqlError,
    error: tqlError ? null : error,
    hasNextPage: query.hasNextPage,
    isFetchingNextPage: query.isFetchingNextPage,
    fetchNextPage: () => {
      if (query.hasNextPage && !query.isFetchingNextPage) {
        void query.fetchNextPage()
      }
    },
  }
}

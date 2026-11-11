import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query'
import { api, unwrap } from '@/shared/api/client.ts'

// BLOCK 1: keys — results by query text: every distinct TQL is its own cached list, so going back to an earlier
// query (or a filter clicked twice) shows its results at once
export const searchKeys = {
  all: ['search'] as const,
  results: (tql: string) => [...searchKeys.all, 'results', tql] as const,
  complete: (tql: string, cursor: number) => [...searchKeys.all, 'complete', tql, cursor] as const,
  suggest: (q: string) => [...searchKeys.all, 'suggest', q] as const,
  filters: () => ['filters'] as const,
}

export const PAGE_SIZE = 50

// BLOCK 2: results — cursor pages (point-in-time + search_after on the server, 05-search §3). A TQL error is a
// 400 with a position: no retry, the same text would fail the same way.
export function searchQuery(tql: string) {
  return infiniteQueryOptions({
    queryKey: searchKeys.results(tql),
    queryFn: ({ pageParam }) =>
      unwrap(
        api.POST('/api/v1/search', {
          body: { tql, limit: PAGE_SIZE, ...(pageParam ? { cursor: pageParam } : {}) },
        }),
      ),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor ?? null,
    retry: false,
  })
}

// BLOCK 3: the editor's autocomplete — the text up to the caret, and the caret. A minute is plenty: the same
// prefix always offers the same fields and operators, and value lists change rarely.
export function completeQuery(tql: string, cursor: number) {
  return queryOptions({
    queryKey: searchKeys.complete(tql, cursor),
    queryFn: () => unwrap(api.GET('/api/v1/search/tql/complete', { params: { query: { tql, cursor } } })),
    staleTime: 60_000,
    retry: false,
  })
}

export function suggestQuery(q: string) {
  return queryOptions({
    queryKey: searchKeys.suggest(q),
    queryFn: () => unwrap(api.GET('/api/v1/search/suggest', { params: { query: { q } } })),
    staleTime: 30_000,
  })
}

export function filtersQuery() {
  return queryOptions({
    queryKey: searchKeys.filters(),
    queryFn: () => unwrap(api.GET('/api/v1/filters')),
  })
}

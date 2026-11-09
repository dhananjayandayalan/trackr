import { QueryCache, QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/errors.ts'
import { showToast } from '@/shared/ui/Toast.tsx'

/** A new client per app (and per test, so tests never share cached data). Defaults from 07-frontend §3. */
export function createQueryClient() {
  return new QueryClient({
    // BLOCK 1: one place for errors nobody is looking at. A query that already has data and fails to
    // refresh in the background keeps showing the old data — the screen has no error to render, so we
    // toast. A first load that fails is the screen's to show (error component or inline message).
    queryCache: new QueryCache({
      onError(error, query) {
        if (query.state.data !== undefined) {
          showToast(`Couldn't refresh: ${error.message}`, 'error')
        }
      },
    }),
    defaultOptions: {
      queries: {
        // BLOCK 2: freshness. For 30 s a cached result is "fresh": navigating back shows it with no request.
        // After that it's "stale": still shown at once, then refetched in the background.
        staleTime: 30_000,
        // unused results stay in memory 5 min, so going back to a screen is instant
        gcTime: 5 * 60_000,
        refetchOnWindowFocus: true,
        // BLOCK 3: retry only what can succeed on retry: network trouble and 5xx, never a 4xx (a 404 stays a 404)
        retry: (failures, error) => failures < 2 && !(error instanceof ApiError && error.status < 500),
      },
    },
  })
}

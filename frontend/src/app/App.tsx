import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import type { createAppRouter } from './router.tsx'

/** The providers around everything: the query cache, then the router (whose loaders use that cache). */
export function App({
  queryClient,
  router,
}: {
  queryClient: QueryClient
  router: ReturnType<typeof createAppRouter>
}) {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
}

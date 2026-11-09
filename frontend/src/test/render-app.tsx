import { createMemoryHistory } from '@tanstack/react-router'
import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '@/app/App.tsx'
import { createQueryClient } from '@/app/query-client.ts'
import { createAppRouter } from '@/app/router.tsx'

/**
 * The whole app at a URL, the way main.tsx builds it — real router, loaders, query cache, client — over
 * an in-memory history instead of the address bar, with MSW standing in for the api.
 */
export function renderApp(url: string) {
  const queryClient = createQueryClient()
  const history = createMemoryHistory({ initialEntries: [url] })
  const router = createAppRouter(queryClient, history)
  const user = userEvent.setup()
  const view = render(<App queryClient={queryClient} router={router} />)
  return { ...view, router, queryClient, user }
}

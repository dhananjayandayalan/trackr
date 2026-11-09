// BLOCK 1: global styles, in layer order (08-css-volt §1). Components import their own modules.
import './styles/layers.css'
import './styles/reset.css'
import './styles/fonts.css'
import './styles/tokens.css'
import './styles/base.css'
import './styles/utilities.css'

import { StrictMode, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { meKeys } from './features/auth/queries.ts'
import { setUnauthenticatedHandler } from './shared/api/client.ts'
import { App } from './app/App.tsx'
import { createQueryClient } from './app/query-client.ts'
import { createAppRouter } from './app/router.tsx'

// BLOCK 2: one query client and one router for the app's lifetime
const queryClient = createQueryClient()
const router = createAppRouter(queryClient)

// a 401 in the middle of using the app = the session ended: forget who we were, go to sign-in, come back after
setUnauthenticatedHandler(() => {
  queryClient.removeQueries({ queryKey: meKeys.me })
  void router.navigate({ to: '/login', search: { next: router.state.location.href } })
})

// BLOCK 3: what to show — the app, or in dev the UI gallery at /_ui. import.meta.env.DEV is the literal
// `false` in a build, so the bundler drops the gallery branch entirely.
let page: ReactNode = <App queryClient={queryClient} router={router} />
if (import.meta.env.DEV && location.pathname === '/_ui') {
  const { UiGallery } = await import('./app/UiGallery.tsx')
  page = <UiGallery />
}

// BLOCK 4: mount. StrictMode renders twice in dev to surface impure components — the React Compiler
// relies on components being pure, so it's the check that keeps us honest.
const container = document.getElementById('root')
if (!container) {
  throw new Error('index.html has no #root element')
}
createRoot(container).render(<StrictMode>{page}</StrictMode>)

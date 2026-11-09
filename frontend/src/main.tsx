// BLOCK 1: global styles, in layer order (08-css-volt §1). Components import their own modules.
import './styles/layers.css'
import './styles/reset.css'
import './styles/fonts.css'
import './styles/tokens.css'
import './styles/base.css'
import './styles/utilities.css'

import { StrictMode, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { AppShell } from './app/AppShell.tsx'
import { ShellPreview } from './app/ShellPreview.tsx'

// BLOCK 2: what to show. The router arrives in phase 13; until then the static shell, and in dev the UI
// gallery at /_ui. import.meta.env.DEV is the literal `false` in a build, so the bundler drops the whole
// branch — the gallery's code never reaches production.
let page: ReactNode = (
  <AppShell>
    <ShellPreview />
  </AppShell>
)
if (import.meta.env.DEV && location.pathname === '/_ui') {
  const { UiGallery } = await import('./app/UiGallery.tsx')
  page = <UiGallery />
}

// BLOCK 3: mount. StrictMode renders twice in dev to surface impure components — the React Compiler
// relies on components being pure, so it's the check that keeps us honest.
const container = document.getElementById('root')
if (!container) {
  throw new Error('index.html has no #root element')
}
createRoot(container).render(<StrictMode>{page}</StrictMode>)

// Playwright drives real browsers against the running app (the apps + their infrastructure, e.g. `.\dev.ps1`
// from the repository root, or the CI e2e job). Unlike Vitest's jsdom, there is layout, native drag and drop,
// the Popover API, EventSource: what jsdom can't check is checked here — and every screen is checked with axe.
import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173'
// WebKit (Safari's engine) runs in CI; locally only when asked, as it needs `pnpm exec playwright install webkit`
const webkit = Boolean(process.env.CI ?? process.env.E2E_WEBKIT)

export default defineConfig({
  testDir: './e2e',
  // the tests share the dev database (moving MAR's cards), so one at a time
  workers: 1,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  // BLOCK 1: sign Ada and Grace in once, through the api, and keep their cookies in e2e/.auth/*.json: tests open
  // contexts from those files instead of typing a password each time (and stay under the sign-in rate limit)
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  // BLOCK 2: browsers — Chrome locally (the one you have, no download), Playwright's Chromium in CI, plus WebKit
  projects: [
    {
      name: 'chrome',
      use: {
        ...devices['Desktop Chrome'],
        ...(process.env.CI ? {} : { channel: 'chrome' }),
        viewport: { width: 1440, height: 1000 },
      },
    },
    ...(webkit
      ? [
          {
            name: 'webkit',
            use: { ...devices['Desktop Safari'], viewport: { width: 1440, height: 1000 } },
            // the drag-and-drop, slow-network and two-typist tests use Chrome's DevTools protocol (network
            // throttling) and Chrome's drag timing; WebKit runs the screen, accessibility and form flows
            testIgnore: ['**/collab.spec.ts', '**/board.spec.ts'],
          },
        ]
      : []),
  ],
  // BLOCK 3: the web server. Locally `.\dev.ps1` already serves Vite on :5173 and Playwright reuses it. In CI
  // nothing is running yet, so it serves the production build with `vite preview` (which proxies /api and
  // /collab like the dev server). The apps themselves are started by the CI job before this.
  webServer: {
    command: 'pnpm preview --port 5173 --strictPort',
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})

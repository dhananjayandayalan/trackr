// Playwright drives real browsers against the running app (pnpm dev + the api + its infrastructure — e.g.
// `.\dev.ps1` from the repository root). Unlike Vitest's jsdom, there is layout, native drag and drop,
// the Popover API, EventSource: what jsdom can't check is checked here.
import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  // the tests share the dev database (moving MAR's cards), so one at a time
  workers: 1,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chrome',
      // locally: the Chrome you already have (no browser download); CI (phase 18) uses Playwright's Chromium
      use: {
        ...devices['Desktop Chrome'],
        ...(process.env.CI ? {} : { channel: 'chrome' }),
        viewport: { width: 1440, height: 1000 },
      },
    },
  ],
})

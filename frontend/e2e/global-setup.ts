import { mkdirSync } from 'node:fs'
import { request, type FullConfig } from '@playwright/test'
import { ADA, GRACE, PASSWORD, authFile } from './helpers.ts'

/**
 * Runs once before all tests: signs each seed user in through the api — the two calls the login page makes
 * (GET /api/v1/me for the XSRF-TOKEN cookie, then POST /api/v1/auth/login with it echoed in X-XSRF-TOKEN) — and
 * saves the cookies as a Playwright storage state. screens.spec.ts covers the sign-in screen itself.
 */
export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0]?.use.baseURL ?? 'http://localhost:5173'
  mkdirSync('e2e/.auth', { recursive: true })
  for (const who of [ADA, GRACE]) {
    const context = await request.newContext({ baseURL })
    await context.get('/api/v1/me')
    const xsrf = (await context.storageState()).cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value
    const response = await context.post('/api/v1/auth/login', {
      data: { email: who.email, password: PASSWORD, remember: false },
      headers: xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf) } : {},
    })
    if (!response.ok()) {
      throw new Error(
        `signing in ${who.email} failed: ${String(response.status())} ${await response.text()} (is the app running?)`,
      )
    }
    await context.storageState({ path: authFile(who) })
    await context.dispose()
  }
}

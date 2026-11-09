// Runs before every test file (vitest.config.ts setupFiles).
// jest-dom's matchers (toBeInTheDocument, toHaveAttribute…) on Vitest's expect, with their types.
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw/server.ts'

// jsdom has no layout, so no scrolling: the router's scroll restoration would log "not implemented" each time
window.scrollTo = () => undefined

// the fake api: a request no handler answers fails the test, so nothing reaches the real network by accident
beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' })
})

// unmount what a test rendered, drop the handlers it added, and forget its cookies
afterEach(() => {
  cleanup()
  server.resetHandlers()
  for (const cookie of document.cookie.split('; ').filter(Boolean)) {
    document.cookie = `${cookie.split('=')[0] ?? ''}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
  }
})

afterAll(() => {
  server.close()
})

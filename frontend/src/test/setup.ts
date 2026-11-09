// Runs before every test file (vitest.config.ts setupFiles).
// jest-dom's matchers (toBeInTheDocument, toHaveAttribute…) on Vitest's expect, with their types.
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// unmount what a test rendered, so the next test starts with an empty document
afterEach(() => {
  cleanup()
})

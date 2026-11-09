// Vitest runs the tests through the same Vite pipeline (React Compiler, CSS Modules, @ alias),
// so a test sees the components exactly as the app does.
import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.ts'

export default defineConfig((env) =>
  mergeConfig(viteConfig(env), {
    test: {
      // a fake DOM in Node; enough for rendering and events, not for layout (that's Playwright, phase 18)
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      restoreMocks: true,
    },
  }),
)

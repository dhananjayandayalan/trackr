// BLOCK 1: imports + defineConfig. The function form gets the command: 'serve' (pnpm dev) or 'build'.
import { fileURLToPath, URL } from 'node:url'
import babel from '@rolldown/plugin-babel'
import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import browserslist from 'browserslist'
import { browserslistToTargets } from 'lightningcss'
import { defineConfig } from 'vite'

const api = 'http://localhost:8080'

export default defineConfig(({ command }) => ({
  // BLOCK 2: React, plus the React Compiler. plugin-react v6 no longer runs Babel itself (Vite 8 transforms
  // with Oxc); the compiler is still a Babel plugin, so @rolldown/plugin-babel runs it, with the preset
  // plugin-react exports (it only feeds .jsx/.tsx files that contain components or hooks to Babel).
  plugins: [react(), babel({ presets: [reactCompilerPreset()] })],

  // BLOCK 3: `@/…` means `src/…` (tsconfig.app.json "paths" says the same to the type checker)
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },

  // BLOCK 4: CSS through Lightning CSS: nesting, light-dark() etc. lowered only where the browserslist
  // targets in package.json need it, then minified. CSS Modules class names: readable in dev
  // (Button__root), unique in the build (a hash of the file, then the local name).
  css: {
    transformer: 'lightningcss',
    lightningcss: {
      targets: browserslistToTargets(browserslist()),
      cssModules: {
        pattern: command === 'serve' ? '[name]__[local]' : '[hash]_[local]',
        // container and grid-area names are shared between files on purpose (AppShell declares the
        // `shell` container and the top/side/main areas; TopNav and Sidebar use them). Scoping them per
        // file, Lightning CSS's default, would silently break every cross-file query and placement.
        container: false,
        grid: false,
      },
    },
    devSourcemap: true,
  },

  // BLOCK 5: dev server — the browser only talks to :5173, Vite forwards the rest (07-frontend §9).
  // Same origin in dev, so cookies and CSRF behave as in production behind Caddy.
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      // SSE streams through a normal HTTP proxy; no WebSocket upgrade needed
      '/api': { target: api, ws: false },
      '/oauth2': { target: api },
      '/login/oauth2': { target: api },
      // the collaborative editor's WebSocket (phase 16)
      '/collab': { target: 'ws://localhost:8081', ws: true },
    },
  },

  // BLOCK 6: build — browsers in Baseline "widely available", sourcemaps for error reports, and stable
  // vendor chunks: React changes far less often than our code, so its chunk stays cached across releases
  build: {
    target: 'baseline-widely-available',
    sourcemap: true,
    cssMinify: 'lightningcss',
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            { name: 'vendor', test: /node_modules[\\/](react|react-dom|scheduler|@tanstack)[\\/]/ },
            // Tiptap + Yjs arrive in phase 16 and load only when an issue opens
            {
              name: 'editor',
              test: /node_modules[\\/](@tiptap|prosemirror-[a-z-]+|yjs|y-[a-z-]+|lib0)[\\/]/,
            },
          ],
        },
      },
    },
  },
}))

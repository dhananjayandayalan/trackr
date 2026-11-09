# Phase 12 · Frontend foundation (Vite, TypeScript, React Compiler, VOLT CSS)

**Goal:** a hand-written Vite + React 19 + TypeScript project with the React Compiler, linting, the VOLT token system in cascade layers, the first UI components, and a static app shell that looks like the canvas.
**Spec:** 07-frontend §1, §8–9; 08-css-volt (all). **Time:** 4 days.

## Concepts you'll learn

- **What Vite does** — dev server serving native ES modules + a production bundler (Rolldown in Vite 8).
- **`package.json` fields** — `type: module`, scripts, dependencies vs devDependencies, `packageManager`, `engines`.
- **TypeScript project references** — separate configs for browser code and Node tooling.
- **`moduleResolution: "bundler"`, `verbatimModuleSyntax`, `isolatedModules`** — what they enforce and why Vite needs them.
- **The React Compiler** — automatic memoisation at build time; the rules of React it relies on.
- **CSS Modules** — locally scoped class names, compiled away.
- **Cascade layers + custom properties** — the VOLT architecture.
- **ESLint flat config.**

## Files in this phase (order)

1. `frontend/package.json`
2. `frontend/.npmrc` (optional: `engine-strict=true`)
3. `frontend/tsconfig.json`, `tsconfig.app.json`, `tsconfig.node.json`
4. `frontend/vite.config.ts`
5. `frontend/index.html`
6. `frontend/src/vite-env.d.ts`
7. `frontend/src/styles/layers.css`, `reset.css`, `fonts.css`, `tokens.css`, `base.css`, `utilities.css`
8. `frontend/public/fonts/*.woff2` (download Archivo, Inter, JetBrains Mono — OFL licensed — and self-host)
9. `frontend/src/main.tsx`
10. `frontend/src/shared/ui/Icon.tsx` (+ `icons.ts` path data copied from the canvas Icons board)
11. `frontend/src/shared/ui/Button.tsx` + `Button.module.css` + `Button.test.tsx`
12. `Lozenge`, `TypeIcon`, `PriorityIcon`, `Avatar`, `Kbd` (+ modules)
13. `frontend/src/app/AppShell.tsx` + `AppShell.module.css`, `TopNav.tsx`, `Sidebar.tsx` (static data for now)
14. `frontend/src/app/UiGallery.tsx` — a dev-only page rendering every component in every state (your mini Storybook)
15. `frontend/eslint.config.js`, `frontend/.prettierrc`
16. `frontend/vitest.config.ts` (or a `test` block in vite config) + `src/test/setup.ts`

### File 1 — `package.json` (JSON has no comments: type each top-level key as its own block)

| #   | Key                                  | What                                                                                                                                                                                                                                                                                      |
| --- | ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `name`, `private`, `version`, `type` | `"private": true` stops accidental publish; `"type": "module"` makes `.js` files ES modules                                                                                                                                                                                               |
| 2   | `packageManager`, `engines`          | `"pnpm@10.x.x"`, `"node": ">=24"`                                                                                                                                                                                                                                                         |
| 3   | `scripts`                            | `dev: vite`, `build: tsc -b && vite build`, `preview`, `typecheck: tsc -b --noEmit`, `lint: eslint .`, `test: vitest`, `e2e: playwright test`, `api:types: openapi-typescript http://localhost:8080/v3/api-docs -o src/shared/api/schema.d.ts`                                            |
| 4   | `dependencies`                       | add with `pnpm add react react-dom` now; others in their phases — each `pnpm add` is a block you explain                                                                                                                                                                                  |
| 5   | `devDependencies`                    | `pnpm add -D vite @vitejs/plugin-react babel-plugin-react-compiler typescript @types/react @types/react-dom eslint typescript-eslint eslint-plugin-react-hooks eslint-plugin-jsx-a11y prettier vitest @testing-library/react @testing-library/user-event @testing-library/jest-dom jsdom` |

**Ask yourself:** why is `typescript` a devDependency even though all source is TypeScript?

### File 3 — tsconfigs

`tsconfig.json`: `{ "files": [], "references": [{ "path": "./tsconfig.app.json" }, { "path": "./tsconfig.node.json" }] }`.
`tsconfig.app.json` blocks:
| # | What |
|---|---|
| 1 | target/lib: `ES2023`, `lib: ["ES2023", "DOM", "DOM.Iterable"]` |
| 2 | module system: `module: "ESNext"`, `moduleResolution: "bundler"`, `verbatimModuleSyntax: true`, `isolatedModules: true`, `moduleDetection: "force"`, `allowImportingTsExtensions: true`, `noEmit: true` |
| 3 | JSX: `jsx: "react-jsx"` |
| 4 | strictness: `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noUnusedLocals`, `noUnusedParameters`, `noFallthroughCasesInSwitch` |
| 5 | paths: `baseUrl: "."`, `paths: { "@/*": ["src/*"] }` (mirror in Vite `resolve.alias`) |
| 6 | `include: ["src"]`, `types: ["vite/client", "@testing-library/jest-dom"]` |
`tsconfig.node.json`: same strictness, `lib: ["ES2023"]`, `types: ["node"]`, `include: ["vite.config.ts", "eslint.config.js", "playwright.config.ts"]`.
**Ask yourself:** what does `noUncheckedIndexedAccess` change about `array[0]`'s type, and why is that good for board columns?

### File 4 — `vite.config.ts`

| #   | Cue                                          | What                                                                                                                                                                                                 |
| --- | -------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: imports + defineConfig`         |                                                                                                                                                                                                      |
| 2   | `// BLOCK 2: react plugin with the compiler` | configure `@vitejs/plugin-react` so it runs `babel-plugin-react-compiler` — follow the plugin's **current** docs for Vite 8 (the integration changed in plugin-react v6; don't trust older snippets) |
| 3   | `// BLOCK 3: path alias`                     | `resolve.alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }`                                                                                                                           |
| 4   | `// BLOCK 4: CSS`                            | `css.transformer: 'lightningcss'`, `css.lightningcss.targets` from browserslist, `css.modules.generateScopedName` readable in dev (`[name]__[local]`) and hashed in prod                             |
| 5   | `// BLOCK 5: dev server proxy`               | 07-frontend §9                                                                                                                                                                                       |
| 6   | `// BLOCK 6: build`                          | `target: 'baseline-widely-available'`, `sourcemap: true`, manual chunks per 07-frontend §8 (check the Rolldown-era option name)                                                                      |

### File 5 — `index.html`

Blocks: doctype + `lang="en"` · meta charset/viewport/theme-color (`#0E0E12`) · `<title>TRAKR</title>` · `<link rel="preload" as="font" type="font/woff2" crossorigin href="/fonts/Inter-600.woff2">` for the two most used weights · `<div id="root"></div>` · `<script type="module" src="/src/main.tsx"></script>`.

### File 7 — styles (write in the layer order)

| File            | Blocks                                                                                                                                                                                                                                                                             |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `layers.css`    | the single `@layer` order statement                                                                                                                                                                                                                                                |
| `reset.css`     | `@layer reset { … }` with: `box-sizing: border-box` everywhere · margin reset · media elements `display: block; max-inline-size: 100%` · form controls `font: inherit; color: inherit` · do **not** `all: unset` buttons (it strips focus and semantics — explain why in your log) |
| `fonts.css`     | one `@font-face` per weight, `font-display: swap`, `unicode-range` basic Latin                                                                                                                                                                                                     |
| `tokens.css`    | `@layer tokens { :root { … } }` — one block per token group in 08-css-volt §2 (core colours · neutrals · semantic sets · type · space/size · radius/border/shadow · motion · layout); plus `@property --progress { syntax: '<percentage>'; inherits: false; initial-value: 0%; }`  |
| `base.css`      | 08-css-volt §3, one block per rule group                                                                                                                                                                                                                                           |
| `utilities.css` | `.visually-hidden`, `.stack`, `.cluster`, `.mono`                                                                                                                                                                                                                                  |

**Check:** open DevTools → Elements → Styles: every rule shows its layer; computed values resolve from tokens.

### File 11 — `Button` (component pattern you will repeat ~25 times)

| #   | Cue                      | What                                                                                                                                                                                                                              |
| --- | ------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: props type` | `type ButtonProps = ComponentPropsWithRef<'button'> & { variant?: 'primary' \| 'accent' \| 'secondary' \| 'ghost' \| 'danger'; size?: 'sm' \| 'md' \| 'lg'; icon?: IconName }` — React 19 passes `ref` as a prop, no `forwardRef` |
| 2   | `// BLOCK 2: component`  | renders `<button type="button" data-variant data-size className={styles.root}>`; spreads the rest                                                                                                                                 |
| 3   | `// BLOCK 3: icon slot`  | optional leading `<Icon name aria-hidden>`                                                                                                                                                                                        |

CSS: copy the reference in 08-css-volt §4, then add sizes and `:focus-visible`.
**Test:** renders with role button; `disabled` blocks clicks; variant attribute set.

### File 13 — `AppShell`

Grid from 08-css-volt §6. `TopNav` and `Sidebar` match the canvas components, with static links for now. Sidebar collapses to a top drawer under 48rem via a **container query** on the shell, not a media query — explain the difference.

### File 14 — `UiGallery`

Every component × every variant/state on one page at `/_ui` (dev only; routing arrives in phase 13 — until then render it from `main.tsx` behind `import.meta.env.DEV`). Compare side by side with the canvas.

### File 15 — ESLint flat config

Blocks: ignores (`dist`, `src/shared/api/schema.d.ts`) · `js.configs.recommended` · `tseslint.configs.strictTypeChecked` with `parserOptions.projectService: true` · react-hooks recommended (includes the React Compiler rules in current versions — check) · jsx-a11y recommended · project rules (no default exports except route components; `@typescript-eslint/consistent-type-imports`).

## Decisions taken while building it

- **No design canvas in the repo:** TopNav, Sidebar and the 50-icon set (`icons.ts`, 24×24, 2px square-cap strokes)
  are designed from the tokens and 00-product §3.13. `/_ui` shows them all.
- **Versions:** pnpm 12 (installed; settings `saveExact`/`engineStrict` live in `pnpm-workspace.yaml`, pnpm 10+
  ignores them in `.npmrc`). **TypeScript 6.0**, not 7: TS 7 (the Go port) has no JS compiler API, and
  typescript-eslint's type-aware rules need it (`typescript <6.1`). **ESLint 9**: eslint-plugin-jsx-a11y doesn't
  support ESLint 10 yet. `baseUrl` dropped (deprecated in TS 6; `paths` work without it).
- **React Compiler:** plugin-react v6 no longer runs Babel; the compiler runs through `@rolldown/plugin-babel` with
  `reactCompilerPreset()` (Babel 8). Checked in the build: the app chunk contains the compiler's memo cache.
- **CSS Modules under Lightning CSS:** `css.modules.generateScopedName` is a postcss option and is ignored;
  naming is `css.lightningcss.cssModules.pattern` (`[name]__[local]` in dev, `[hash]_[local]` in builds).
  `container: false` and `grid: false` are required: by default Lightning CSS hashes container and grid-area
  names per file, which silently broke every cross-file `@container shell` query and `grid-area`.
- **Root font size:** the body text style is on `body`, not `html` — `font` on html makes 1rem = 14px and shrinks
  every token and container breakpoint.
- **Chunks:** `build.rolldownOptions.output.codeSplitting.groups` (Rolldown's name for manual chunks).
- **Fonts:** Inter 400–800, Archivo 800/900, JetBrains Mono 700 from Fontsource (Latin subset, OFL files beside
  them); preloaded Inter 500 and 700, the body and button weights the tokens actually use.
- **Tokens added** beyond §2 (all derived from it): `--text-on-dark`, `--line-on-dark`, `--surface-dark(-raised)`,
  `--avatar-violet` (light violet, ink text), `--text-button(-sm/-lg)`, `--control-xs`, `--icon-xs/sm/md`,
  `--border-width`, `--border-thin`, `--shadow-5`, `--shadow-3-on-dark`, `--shadow-bar`, `--space-px`.
- **Lint:** `<ul role="list">` is allowed on purpose (Safari drops list semantics with `list-style: none`).
  No default exports except `*.route.tsx` and config files.

## Done when

- `pnpm dev` shows the static shell looking like the canvas TopNav + Sidebar.
- `pnpm build` passes type-check and outputs hashed assets; inspect `dist/assets` and find your CSS Module class names hashed.
- `pnpm lint` and `pnpm test` are green.
- React DevTools shows the "Memo ✨" compiler badge on your components.

Commit: `phase-12: frontend foundation`.

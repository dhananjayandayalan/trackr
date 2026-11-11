// ESLint flat config: an array of config objects, applied in order; later objects override earlier ones
// for the files they match. No .eslintrc, no "extends" strings — plain imports.
import js from '@eslint/js'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  // BLOCK 1: ignores — build output and generated code
  { ignores: ['dist', 'coverage', 'src/shared/api/schema.d.ts'] },

  // BLOCK 2: JavaScript's own recommended rules
  js.configs.recommended,

  // BLOCK 3: TypeScript, type-aware. projectService finds the tsconfig that owns each file, so rules can
  // ask the type checker ("is this promise awaited?", "can this condition ever be false?").
  ...tseslint.configs.strictTypeChecked,
  ...tseslint.configs.stylisticTypeChecked,
  {
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
      globals: globals.browser,
    },
  },

  // BLOCK 4: React — the rules of hooks plus the React Compiler's rules (purity, no mutation of props or
  // state, no reading refs during render…). Code that breaks them is code the compiler can't memoise.
  reactHooks.configs.flat.recommended,

  // BLOCK 5: accessibility of the JSX itself — alt text, labels, valid ARIA, interactive roles
  jsxA11y.flatConfigs.recommended,

  // BLOCK 6: project rules
  {
    rules: {
      // `import type` for types: they vanish from the output (verbatimModuleSyntax insists too)
      '@typescript-eslint/consistent-type-imports': ['error', { fixStyle: 'inline-type-imports' }],
      // named exports only: one name per thing, found by search and auto-import alike.
      // Route components (phase 13, *.route.tsx) and config files are the exceptions below.
      'no-restricted-syntax': [
        'error',
        {
          selector: 'ExportDefaultDeclaration',
          message: 'Use a named export (default exports only for routes).',
        },
      ],
      // `() => setOpen(!open)` returns void anyway; allow the short form in JSX handlers
      '@typescript-eslint/no-confusing-void-expression': ['error', { ignoreArrowShorthand: true }],
      // template literals with numbers are fine (`${count} unread`)
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],
      // <ul role="list"> on purpose: Safari/VoiceOver stops announcing a list once list-style is none,
      // and reset.css only strips the bullets of lists that say role="list"
      'jsx-a11y/no-redundant-roles': ['error', { ul: ['list'], ol: ['list'] }],
    },
  },
  {
    // Playwright's globalSetup must be a default export, like the config files
    files: ['**/*.route.tsx', '*.config.{js,ts}', 'e2e/global-setup.ts'],
    rules: { 'no-restricted-syntax': 'off' },
  },
  // Playwright tests: a fixture's `use(value)` is Playwright's, not React's use() hook
  {
    files: ['e2e/**'],
    rules: { 'react-hooks/rules-of-hooks': 'off' },
  },

  // BLOCK 7: plain JS (this file) isn't in a tsconfig: lint it without type information, in Node
  {
    files: ['**/*.{js,mjs}'],
    ...tseslint.configs.disableTypeChecked,
    languageOptions: { ...tseslint.configs.disableTypeChecked.languageOptions, globals: globals.node },
  },
)

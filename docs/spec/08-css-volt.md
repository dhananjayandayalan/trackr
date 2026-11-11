# 08 · VOLT in pure CSS

Source of truth for values: the VOLT design canvas (Foundations page). This file turns it into CSS architecture.

## 1. Files and layer order

`index.html` links `public/layers.css` before anything else; `src/main.tsx` then imports, in this order: `reset.css`, `fonts.css`, `tokens.css`, `base.css`, `utilities.css`. Components import their own `*.module.css`. (Layer order is set by the first stylesheet that names the layers. Imported from `main.tsx`, a production build put chunk stylesheets such as Button's ahead of it — found in phase 18.)

```css
/* layers.css — the only file with no layer: it declares the order */
@layer reset, tokens, base, components, utilities;
```

Why layers: a later layer always wins over an earlier one regardless of selector specificity, so utilities beat components and nobody needs `!important`.

## 2. Tokens (`tokens.css`, inside `@layer tokens { :root { … } }`)

### Colour — core

| Token                   | Value                  | Use                                   |
| ----------------------- | ---------------------- | ------------------------------------- |
| `--color-ink`           | `#0E0E12`              | text, borders, shadows, sidebar       |
| `--color-paper`         | `#FFFFFF`              | cards, inputs, panels                 |
| `--color-canvas`        | `#F5F5F2`              | app background                        |
| `--color-primary`       | `#5B2EFF`              | primary actions, links, focus         |
| `--color-primary-hover` | `#4A1FF0`              |                                       |
| `--color-primary-press` | `#3A12D9`              |                                       |
| `--color-on-primary`    | `#FFFFFF`              |                                       |
| `--color-accent`        | `#C6FF3D`              | lime: current location, done, success |
| `--color-pink`          | `#FF2E88`              | today marker, notifications           |
| `--color-cyan`          | `#00D1FF`              | in progress, tasks                    |
| `--color-yellow`        | `#FFD600`              | in review, warnings, stars            |
| `--color-orange`        | `#FF7A1A`              | flagged                               |
| `--color-danger`        | `#E8112D`              | destructive actions                   |
| `--color-danger-text`   | `#C20E26`              | error text on paper                   |
| `--color-danger-soft`   | `#FFF0F2`              | invalid field background              |
| `--color-focus`         | `var(--color-primary)` | focus rings                           |

### Colour — neutral ramp

`--gray-900 #0E0E12 · --gray-800 #1E1E26 · --gray-700 #3D3D46 · --gray-600 #5C5C66 · --gray-500 #8C8C96 · --gray-400 #A8A89E · --gray-300 #CFCFC6 · --gray-200 #EAEAE4 · --gray-100 #F5F5F2 · --gray-0 #FFFFFF`
Semantic aliases: `--text-color #0E0E12`, `--text-muted var(--gray-600)`, `--text-on-dark-muted #A3A3AD`, `--line var(--gray-300)`, `--line-soft #D6D6CE`, `--surface-sunken var(--gray-200)`.

### Colour — semantic sets

| Group                   | Tokens                                                                                                                               |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Status                  | `--status-todo #E4E4DE`, `--status-progress #00D1FF`, `--status-review #FFD600`, `--status-done #C6FF3D`, `--status-blocked #FF3B3B` |
| Issue type              | `--type-story #C6FF3D`, `--type-bug #FF3B3B`, `--type-task #00D1FF`, `--type-epic #5B2EFF` (icon white), `--type-subtask #7DA2FF`    |
| Priority (icon strokes) | `--prio-highest #E8112D`, `--prio-high #E85D00`, `--prio-medium #B57A00`, `--prio-low #0086B3`, `--prio-lowest #6B6B75`              |
| Avatar                  | `--avatar-pink`, `--avatar-cyan`, `--avatar-lime`, `--avatar-yellow`, `--avatar-orange`, `--avatar-violet` (= the pop colours)       |
| Charts                  | `--chart-1 #5B2EFF`, `--chart-2 #FF2E88`, `--chart-3 #008FB0`, `--chart-reference #8C8C96`                                           |

Text on any pop colour is always `--color-ink` (contrast ≥ 5.4:1). Text on `--color-primary` and `--type-epic` is white (6.4:1).

### Type

| Token               | Value                                                             |
| ------------------- | ----------------------------------------------------------------- |
| `--font-display`    | `"Archivo", system-ui, sans-serif`                                |
| `--font-ui`         | `"Inter", system-ui, sans-serif`                                  |
| `--font-mono`       | `"JetBrains Mono", ui-monospace, monospace`                       |
| `--text-display-xl` | `900 4rem/1 var(--font-display)` + `--track-display-xl: -0.04em`  |
| `--text-display`    | `900 3rem/3.25rem var(--font-display)`, track -0.03em             |
| `--text-h1`         | `800 2rem/2.25rem var(--font-display)`, track -0.02em             |
| `--text-h2`         | `800 1.5rem/1.75rem var(--font-display)`, track -0.02em           |
| `--text-h3`         | `800 1.125rem/1.5rem var(--font-display)`                         |
| `--text-body-lg`    | `400 1rem/1.5rem var(--font-ui)`                                  |
| `--text-body`       | `500 0.875rem/1.25rem var(--font-ui)`                             |
| `--text-body-sm`    | `500 0.8125rem/1.125rem var(--font-ui)`                           |
| `--text-label`      | `800 0.75rem/1rem var(--font-ui)` + uppercase + `0.08em` tracking |
| `--text-caption`    | `400 0.75rem/1rem var(--font-ui)`                                 |
| `--text-mono`       | `700 0.75rem/1rem var(--font-mono)`                               |
| `--text-mono-sm`    | `700 0.6875rem/0.875rem var(--font-mono)`                         |

Use with the `font` shorthand: `font: var(--text-h1); letter-spacing: var(--track-h1);`.

### Space, size, radius, border, shadow, motion

- Space (4px base): `--space-1 .25rem` · `-2 .5rem` · `-3 .75rem` · `-4 1rem` · `-5 1.25rem` · `-6 1.5rem` · `-8 2rem` · `-10 2.5rem` · `-12 3rem` · `-16 4rem`.
- Control heights: `--control-sm 2rem`, `--control-md 2.5rem`, `--control-lg 3rem`.
- Radius: `--radius-0 0`, `--radius-sm 2px` (default), `--radius-md 4px` (max), `--radius-round 50%` (radios only).
- Borders: `--border-line 1px solid var(--line-soft)`, `--border-field 1px solid var(--line)`, `--border-strong 2px solid var(--color-ink)`, `--border-drop 2px dashed var(--color-primary)`.
- Shadows: `--shadow-2 2px 2px 0 var(--color-ink)`, `--shadow-3 3px 3px 0 …`, `--shadow-4 4px 4px 0 …`, `--shadow-6 6px 6px 0 …`, `--shadow-drag 6px 6px 0 var(--color-primary)`, `--shadow-modal 10px 10px 0 var(--color-primary)`.
- Motion: `--ease-out cubic-bezier(.2,.8,.2,1)`, `--dur-press 90ms`, `--dur-lift 120ms`, `--dur-panel 180ms`.
- Layout: `--topnav-h 4rem`, `--sidebar-w 15.5rem`, `--content-max 80rem`, `--column-min 17rem`.

Register animatable tokens with `@property` (e.g. `--progress` as `<percentage>`) so they transition smoothly.

## 3. Base layer

- `html { color-scheme: light; font: var(--text-body); color: var(--text-color); background: var(--color-canvas); }` (`light-dark()` values prepared for a future dark theme.)
- `:focus-visible { outline: 2px solid var(--color-focus); outline-offset: 2px; }`
- `h1–h3` use display tokens and `text-wrap: balance`; `p` uses `text-wrap: pretty`.
- `::selection { background: var(--color-accent); color: var(--color-ink); }`
- `@media (prefers-reduced-motion: reduce) { *, ::before, ::after { animation-duration: .01ms !important; transition-duration: .01ms !important; } }` (the one allowed `!important`, inside `@layer base`).

## 4. Component pattern

Each component: `Thing.module.css` wrapped in `@layer components`. Root class `.root`; variants via `data-variant`, sizes via `data-size`, states via ARIA (`[aria-pressed="true"]`, `[aria-invalid="true"]`, `[aria-current="page"]`, `:disabled`).

Button (reference implementation, write it in phase 12):

```css
@layer components {
  .root {
    --_bg: var(--color-paper);
    --_fg: var(--color-ink);
    --_shadow: none;
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    block-size: var(--control-md);
    padding-inline: var(--space-4);
    font: 700 0.875rem/1 var(--font-ui);
    background: var(--_bg);
    color: var(--_fg);
    border: var(--border-strong);
    border-radius: var(--radius-sm);
    box-shadow: var(--_shadow);
    transition:
      transform var(--dur-lift) var(--ease-out),
      box-shadow var(--dur-lift) var(--ease-out);

    &[data-variant="primary"] {
      --_bg: var(--color-primary);
      --_fg: var(--color-on-primary);
      --_shadow: var(--shadow-3);
    }
    &[data-variant="accent"] {
      --_bg: var(--color-accent);
      --_shadow: var(--shadow-3);
    }
    &[data-variant="ghost"] {
      border-color: transparent;
    }
    &[data-variant="danger"] {
      --_bg: var(--color-danger);
      --_fg: #fff;
      --_shadow: var(--shadow-3);
    }

    &:hover:not(:disabled) {
      transform: translate(-2px, -2px);
      --_shadow: 5px 5px 0 var(--color-ink);
    }
    &:active:not(:disabled) {
      transform: translate(3px, 3px);
      --_shadow: none;
      transition-duration: var(--dur-press);
    }
    &:disabled {
      --_bg: var(--status-todo);
      --_fg: var(--gray-500);
      --_shadow: none;
      border-color: var(--line);
    }
  }
}
```

Private custom properties start with `--_` and are set by variants — one declaration block, many looks.

## 5. Modern features and where they are used

| Feature                                                   | Where                                                                                                                                                                                                                  |
| --------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Native nesting                                            | every component file                                                                                                                                                                                                   |
| Cascade layers                                            | global architecture                                                                                                                                                                                                    |
| Container queries                                         | `IssueCard` (`container-type: inline-size` on the column; card hides epic tag under 14rem), details panel two-column → one-column                                                                                      |
| `:has()`                                                  | `.field:has(:user-invalid)` error styling, `.row:has(input:checked)` selected rows, `.column:has([data-dragging])` drop highlight. (WIP over-limit uses a `data-over-limit` attribute because limits vary per column.) |
| `color-mix()`                                             | hover tints: `color-mix(in oklch, var(--color-primary) 12%, var(--color-paper))`                                                                                                                                       |
| Popover API                                               | Menu, status dropdown, notifications, tooltip (`popover="hint"` where supported, fallback `auto`)                                                                                                                      |
| Anchor positioning                                        | menus/tooltips: `anchor-name: --status-btn` on the trigger, `position-anchor` + `position-area: block-end span-inline-end` + `position-try-fallbacks: flip-block` on the popover                                       |
| `<dialog>` + `::backdrop`                                 | CreateIssueDialog, confirm dialogs, command palette                                                                                                                                                                    |
| `@starting-style` + `transition-behavior: allow-discrete` | popover/dialog enter and exit animations                                                                                                                                                                               |
| View Transitions                                          | page changes; `view-transition-name: issue-panel` on the side panel                                                                                                                                                    |
| Scroll-driven animations                                  | top nav gains `--shadow-2` once the page scrolls (`animation-timeline: scroll()`)                                                                                                                                      |
| `subgrid`                                                 | backlog rows align columns (key, summary, epic, status, points, priority, avatar)                                                                                                                                      |
| `field-sizing: content`                                   | comment textarea grows with content                                                                                                                                                                                    |
| `text-wrap: balance/pretty`                               | headings / paragraphs                                                                                                                                                                                                  |
| `@scope`                                                  | Tiptap editor content styles: `@scope (.editor) to (.mention)`                                                                                                                                                         |
| Logical properties                                        | everywhere (`margin-block`, `inset-inline-start`)                                                                                                                                                                      |

## 6. Layout recipes

- App shell: `grid-template: "top top" var(--topnav-h) "side main" 1fr / var(--sidebar-w) minmax(0, 1fr)`; under 48rem the sidebar becomes a top drawer.
- Board: horizontal `grid-auto-flow: column; grid-auto-columns: minmax(var(--column-min), 1fr); overflow-x: auto; scroll-snap-type: x proximity`.
- Utilities (`utilities.css`): `.visually-hidden`, `.stack` (flex column + `gap: var(--stack-gap, var(--space-4))`), `.cluster` (flex wrap + gap), `.mono`.

## 7. Animation inventory

| Name    | Where                        | Spec                                                         |
| ------- | ---------------------------- | ------------------------------------------------------------ |
| press   | buttons                      | translate 3px, shadow → none, 90 ms                          |
| lift    | cards on hover               | translate -2px, shadow-4, 120 ms                             |
| drag    | dragged card preview         | rotate(-2deg), shadow-drag                                   |
| flash   | card changed by someone else | background from `--color-accent` to paper over 1 s           |
| panel   | issue side panel             | view transition slide from inline-end, 180 ms                |
| popover | menus                        | opacity + translateY(-4px) → 0 via `@starting-style`, 120 ms |

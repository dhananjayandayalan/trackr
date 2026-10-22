---
applyTo: "frontend/src/**/*.css"
---

# CSS rules — pure modern CSS, VOLT design system (learning mode applies)

Source: `docs/spec/08-css-volt.md` and the VOLT design canvas.

- Layer order is declared once in `src/styles/layers.css`: `@layer reset, tokens, base, components, utilities;`. Every stylesheet puts its rules inside one of these layers. Component modules use `@layer components { … }`.
- Use only tokens (`var(--color-*)`, `var(--space-*)`, `var(--radius-*)`, `var(--shadow-*)`, `var(--font-*)`, `var(--text-*)`). No raw hex, px spacing or font names inside components. If a token is missing, add it to `tokens.css` first and say so.
- Corners: `var(--radius-sm)` (2px) by default; never more than `var(--radius-md)` (4px). Radios are the only round control.
- Depth comes from hard offset shadows (`--shadow-2` … `--shadow-6`), never blur.
- Use native nesting (`&:hover`, `&[aria-pressed="true"]`). Style state through ARIA/data attributes, not extra classes.
- Prefer: container queries for component responsiveness, `:has()` for parent state, `color-mix()` for tints, `light-dark()` for theme, logical properties (`padding-inline`, `margin-block`), `text-wrap: balance` on headings.
- Menus and tooltips: Popover API + anchor positioning (`anchor-name`, `position-anchor`, `position-area`, `position-try-fallbacks`).
- Motion: respect `@media (prefers-reduced-motion: reduce)`.
- Focus: every interactive element has a visible `:focus-visible` style using `--color-focus`.
- When explaining a rule, name the CSS feature and its browser support in one line.

/// <reference types="vite/client" />
// vite/client types import.meta.env, asset imports, and `*.module.css` as `{ readonly [key: string]: string }`.

// CSS custom properties passed through `style` (the only inline style we allow, 07-frontend):
// style={{ '--progress': '40%' }} type-checks instead of failing as an unknown CSS property.
import 'react'

declare module 'react' {
  // an augmentation adds members to the existing interface, so it can't be written as a Record
  // eslint-disable-next-line @typescript-eslint/consistent-indexed-object-style
  interface CSSProperties {
    [customProperty: `--${string}`]: string | number | undefined
  }
}

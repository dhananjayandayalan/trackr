---
applyTo: "frontend/src/**/*.{ts,tsx}"
---

# Frontend React + TypeScript rules (learning mode applies — one block at a time, then explain)

## Structure (see `docs/spec/07-frontend.md`)
- `src/app/` — router, query client, providers, app shell.
- `src/features/<feature>/` — screens, feature components, queries (`queries.ts`), mutations (`mutations.ts`).
- `src/shared/api/` — generated `schema.d.ts` (never edit by hand), `client.ts`, `sse.ts`, `errors.ts`.
- `src/shared/ui/` — design-system components from the VOLT canvas (Button, Lozenge, Avatar, Icon, Menu, Dialog, Field, Tabs, IssueCard…).
- `src/shared/lib/` — pure helpers (`rank.ts`, `dates.ts`, `ids.ts`).

## Rules
- TypeScript `strict`, no `any`, no non-null `!` unless a comment explains why.
- The React Compiler memoises for us: **do not write `useMemo`, `useCallback` or `React.memo`** unless a comment explains a measured need.
- Server data lives only in TanStack Query. Client-only UI state uses `useState`/`useReducer`; share it with context only when two distant components need it. No Redux, no Zustand.
- Every query key comes from a key factory in the feature's `queries.ts` (e.g. `issueKeys.detail(key)`).
- Mutations that change visible data are optimistic: `onMutate` snapshot + patch cache, `onError` roll back, `onSettled` invalidate the narrowest key.
- Route loaders call `queryClient.ensureQueryData` so navigation renders with data.
- Forms use native `<form>` + React 19 actions (`useActionState`, `useFormStatus`). No form library.
- Accessibility: real `<button>`, `<a href>`, `<label>`; icon-only buttons need `aria-label`; menus use the Popover API; dialogs use `<dialog>`.
- Styling: import `./Thing.module.css` as `styles`; no inline `style` except for truly dynamic values (passed as CSS custom properties, e.g. `style={{ '--progress': pct }}`).

## Explain these concepts when they first appear
Rendering vs committing, the React Compiler, query keys and cache invalidation, optimistic updates and rollback, route loaders, Server-Sent Events, fractional indexing, CRDTs.

# Phase 13 · Frontend data layer, routing, auth and the first screens

**Goal:** typed API calls generated from OpenAPI, TanStack Query + Router wired with loaders and prefetching, sign-in/sign-up working against the real api, and the Home, Projects and Create Project screens built from the canvas.
**Spec:** 07-frontend §2–3, §7; 03-api §1–3.2; 00-product §3.1–3.4. **Time:** 5 days.

## Concepts you'll learn

- **Generated types from a contract** — the backend changes, `pnpm api:types`, the compiler tells you what broke.
- **Fetch middleware** — one place for CSRF headers and error translation.
- **Query keys, staleness, caching, deduplication.**
- **Route loaders + `ensureQueryData`** — render with data, no spinners on navigation.
- **Preloading on intent** — hover = fetch.
- **Search-param state** — URL as the source of truth for filters and tabs.
- **React 19 form actions** — `useActionState`, `useFormStatus`, progressive enhancement.
- **MSW** — mock the network, not your modules.

## Files in this phase

1. `pnpm add @tanstack/react-query @tanstack/react-router openapi-fetch zod` · `pnpm add -D openapi-typescript @tanstack/react-query-devtools @tanstack/router-devtools msw`
2. `src/shared/api/schema.d.ts` (generated — run `pnpm api:types` with the api running)
3. `src/shared/api/errors.ts`
4. `src/shared/api/client.ts`
5. `src/app/query-client.ts`
6. `src/app/router.tsx`
7. `src/app/App.tsx` + update `main.tsx`
8. `src/features/auth/queries.ts` (`meQuery`), `mutations.ts`, `LoginPage.tsx` + css
9. `src/shared/ui/Toast.tsx` (+ `useToast` context) — uses the Popover API (`popover="manual"`) so toasts render in the top layer
10. `src/features/home/HomePage.tsx`, `StatTiles.tsx`, `WorkTabs.tsx`, `RecentProjects.tsx`, `ActivityFeed.tsx`, `queries.ts`
11. `src/features/projects/ProjectsPage.tsx`, `ProjectCard.tsx`, `CreateProjectPage.tsx`, `TemplatePicker.tsx`, `ColorSwatches.tsx`, `queries.ts`, `mutations.ts`
12. Shared UI added now: `Tabs`, `Segmented`, `TextInput`, `Select`, `Textarea`, `Field`, `EmptyState`, `Progress`, `AvatarGroup`
13. `src/test/msw/handlers.ts`, `src/test/msw/server.ts`, update `src/test/setup.ts`
14. Tests

### File 4 — `client.ts`

| #   | Cue                                   | What                                                                                                                                                                                       |
| --- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | `// BLOCK 1: create the typed client` | `export const api = createClient<paths>({ baseUrl: '' , credentials: 'same-origin' })`                                                                                                     |
| 2   | `// BLOCK 2: CSRF middleware`         | `onRequest`: for non-GET methods read `XSRF-TOKEN` from `document.cookie` and set `X-XSRF-TOKEN`                                                                                           |
| 3   | `// BLOCK 3: problem middleware`      | `onResponse`: if `!response.ok` and content-type is `application/problem+json` → parse → `throw new ApiError(problem)`; 401 → redirect to `/login?next=` (only once — guard against loops) |
| 4   | `// BLOCK 4: unwrap helper`           | `async function unwrap<T>(p: Promise<{ data?: T; error?: unknown }>): Promise<T>` so query functions stay one-liners                                                                       |

**Ask yourself:** why read the CSRF token from the cookie at request time instead of once at startup?

### File 5 — `query-client.ts`

Defaults from 07-frontend §3. Add a global `QueryCache({ onError })` that toasts only for background refetch errors (foreground errors are shown by the screen).

### File 6 — `router.tsx`

| #   | Cue                                   | What                                                                                                                                                                                                                                                                                    |
| --- | ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: root route with context` | `createRootRouteWithContext<{ queryClient: QueryClient }>()` — the loader context gives every route access to the query client                                                                                                                                                          |
| 2   | `// BLOCK 2: auth gate`               | `_authed` layout route whose `beforeLoad` does `context.queryClient.ensureQueryData(meQuery())` and redirects to `/login` on `ApiError` 401                                                                                                                                             |
| 3   | `// BLOCK 3: public routes`           | `/login`                                                                                                                                                                                                                                                                                |
| 4   | `// BLOCK 4: app routes`              | `/`, `/projects`, `/projects/new` under `_authed` with `AppShell` as the layout component; each `loader` ensures its queries; components lazy-loaded                                                                                                                                    |
| 5   | `// BLOCK 5: project routes (stubs)`  | `/p/$projectKey` layout + child routes returning "coming in phase 14" placeholders                                                                                                                                                                                                      |
| 6   | `// BLOCK 6: create router`           | `createRouter({ routeTree, context: { queryClient }, defaultPreload: 'intent', defaultPreloadStaleTime: 0, scrollRestoration: true })` and the `declare module '@tanstack/react-router' { interface Register { router: typeof router } }` block — explain what module augmentation does |

**Ask yourself:** why `defaultPreloadStaleTime: 0` when TanStack Query already caches? (Let Query decide freshness, not the router.)

### File 8 — `LoginPage` with actions

| #   | What                                                                                                                                                                                    |
| --- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Tabs (Sign in / Create account) as a `Segmented` bound to `?mode=` search param                                                                                                         |
| 2   | `const [state, formAction] = useActionState(loginAction, { errors: {} })` where `loginAction(prev, formData)` calls the mutation and returns field errors from the problem's `errors[]` |
| 3   | `<form action={formAction}>` with real `<label>`s, `autocomplete` attributes, `required`, `minLength={10}`                                                                              |
| 4   | `SubmitButton` using `useFormStatus()` for the pending state                                                                                                                            |
| 5   | OAuth buttons are plain links: `<a href="/oauth2/authorization/google">`                                                                                                                |
| 6   | On success: `queryClient.setQueryData(meKeys.me, me)` then `navigate({ to: search.next ?? '/' })`                                                                                       |

### Screens (Home, Projects, CreateProject)

Build them from the canvas artboards 02–04. For each screen:

1. write `queries.ts` (keys + query options) first,
2. then the route loader,
3. then the page skeleton (layout grid + CSS module),
4. then each sub-component as its own block group.
   Grid/list toggle and Home tabs live in **search params** (`validateSearch` with a zod schema) so the back button and shared links work.
   Create Project: key suggestion uses a debounced query (`useQuery` with `enabled: name.length > 1` and the name in the key) — no manual debounce library; use `useDeferredValue(name)` and explain how it differs from debouncing.

## Tests

- `client.test.ts` (MSW): CSRF header added on POST; problem → `ApiError` with code; 401 triggers redirect once.
- `LoginPage.test.tsx`: validation messages, server field errors rendered next to fields, pending state.
- `HomePage.test.tsx`: tab switch updates the URL and data.
- `CreateProjectPage.test.tsx`: template selection, key suggestion, 409 `PROJECT_KEY_TAKEN` shows on the key field.

## Done when

- Fresh browser → `/` redirects to `/login` → sign up → land on Home with real data (from the dev seed).
- Hovering "Projects" in the top nav fires the projects request before the click (Network tab).
- Changing a backend DTO and re-running `pnpm api:types` produces type errors in exactly the places that need updating.

Commit: `phase-13: data layer, routing, auth, first screens`.

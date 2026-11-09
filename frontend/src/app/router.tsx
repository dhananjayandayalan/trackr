import type { QueryClient } from '@tanstack/react-query'
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  lazyRouteComponent,
  redirect,
  type RouterHistory,
} from '@tanstack/react-router'
import { z } from 'zod'
import { meQuery } from '@/features/auth/queries.ts'
import { activityQuery, statsQuery, workQuery, workTabs } from '@/features/home/queries.ts'
import { projectQuery, projectsQuery } from '@/features/projects/queries.ts'
import { isApiError } from '@/shared/api/errors.ts'
import { safeNext } from '@/shared/lib/urls.ts'
import { AppLayout, ComingSoon, ProjectLayout, RootLayout, RouteError } from './layouts.tsx'

// BLOCK 1: the root route, with a typed context. Every route's beforeLoad and loader receives it: the query
// client is how a loader puts data in the cache before the page renders.
export interface RouterContext {
  queryClient: QueryClient
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
  errorComponent: RouteError,
})

// BLOCK 2: the auth gate — a pathless layout route (an id, no path) that every signed-in screen sits under.
// beforeLoad runs before any child loads: ask /me (from the cache after the first time); a 401 sends the
// browser to /login and remembers where it was going.
const authedRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: '_authed',
  beforeLoad: async ({ context, location }) => {
    try {
      await context.queryClient.query({ ...meQuery(), staleTime: 'static' })
    } catch (error) {
      if (isApiError(error) && error.status === 401) {
        // eslint-disable-next-line @typescript-eslint/only-throw-error -- the router's redirect is thrown by design
        throw redirect({ to: '/login', search: { next: location.href } })
      }
      throw error
    }
  },
})

// BLOCK 3: public routes. The login page asks /me too: a signed-in user goes straight on, and a signed-out
// one gets the XSRF-TOKEN cookie with the 401, which the sign-in POST must echo back.
const loginSearch = z.object({
  next: z.string().optional().catch(undefined),
  mode: z.enum(['signin', 'signup']).optional().catch(undefined),
})

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'login',
  validateSearch: loginSearch,
  beforeLoad: async ({ context, search }) => {
    try {
      await context.queryClient.query({ ...meQuery(), staleTime: 'static' })
    } catch (error) {
      if (isApiError(error) && error.status === 401) {
        return
      }
      throw error
    }
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- the router's redirect is thrown by design
    throw redirect({ href: safeNext(search.next) })
  },
  component: lazyRouteComponent(() => import('@/features/auth/LoginPage.tsx'), 'LoginPage'),
})

// BLOCK 4: app routes — inside the shell. Each loader fills the cache before the page renders, so it renders
// with data instead of a spinner. `query({ ...options, staleTime: 'static' })` (what ensureQueryData was):
// whatever is cached is good enough to render — fetch only what's missing; the page's useSuspenseQuery then
// refreshes stale data in the background (stale-while-revalidate). With preloading on intent,
// a hover over a link already runs the loader. Components are lazy: one chunk per screen.
const appRoute = createRoute({ getParentRoute: () => authedRoute, id: '_app', component: AppLayout })

const homeRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/',
  // the tab lives in the URL: back button, reload and a shared link all keep it
  validateSearch: z.object({ tab: z.enum(workTabs).optional().catch(undefined) }),
  loaderDeps: ({ search }) => ({ tab: search.tab ?? 'assigned' }),
  loader: ({ context: { queryClient }, deps }) =>
    Promise.all([
      queryClient.query({ ...statsQuery(), staleTime: 'static' }),
      queryClient.infiniteQuery({ ...workQuery(deps.tab), staleTime: 'static' }),
      queryClient.query({ ...projectsQuery(), staleTime: 'static' }),
      queryClient.query({ ...activityQuery(), staleTime: 'static' }),
    ]),
  component: lazyRouteComponent(() => import('@/features/home/HomePage.tsx'), 'HomePage'),
})

const projectsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: 'projects',
  validateSearch: z.object({
    view: z.enum(['grid', 'list']).optional().catch(undefined),
    q: z.string().optional().catch(undefined),
  }),
  loader: ({ context: { queryClient } }) => queryClient.query({ ...projectsQuery(), staleTime: 'static' }),
  component: lazyRouteComponent(() => import('@/features/projects/ProjectsPage.tsx'), 'ProjectsPage'),
})

const newProjectRoute = createRoute({
  getParentRoute: () => appRoute,
  path: 'projects/new',
  component: lazyRouteComponent(
    () => import('@/features/projects/CreateProjectPage.tsx'),
    'CreateProjectPage',
  ),
})

const issuesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: 'issues',
  component: () => <ComingSoon title="Issues & filters" phase={17} />,
})

const dashboardsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: 'dashboards',
  component: () => <ComingSoon title="Dashboards" phase={17} />,
})

// BLOCK 5: project routes — the shell with the project sidebar; the screens arrive in later phases
const projectRoute = createRoute({
  getParentRoute: () => authedRoute,
  path: 'p/$projectKey',
  loader: ({ context: { queryClient }, params }) =>
    queryClient.query({ ...projectQuery(params.projectKey), staleTime: 'static' }),
  component: ProjectLayout,
})

const projectIndexRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: '/',
  beforeLoad: ({ params }) => {
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- the router's redirect is thrown by design
    throw redirect({ to: '/p/$projectKey/board', params })
  },
})

// one route each (not a .map over a list): each keeps its own literal path, so its params are typed exactly —
// a link to /p/$projectKey/board needs projectKey and nothing else
const boardRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'board',
  component: () => <ComingSoon title="Board" phase={14} />,
})
const backlogRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'backlog',
  component: () => <ComingSoon title="Backlog" phase={14} />,
})
const timelineRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'timeline',
  component: () => <ComingSoon title="Timeline" phase={17} />,
})
const reportsRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'reports',
  component: () => <ComingSoon title="Reports" phase={17} />,
})
const settingsRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'settings/$section',
  component: () => <ComingSoon title="Settings" phase={17} />,
})
const issueRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'issues/$issueKey',
  component: () => <ComingSoon title="Issue" phase={15} />,
})

export const routeTree = rootRoute.addChildren([
  loginRoute,
  authedRoute.addChildren([
    appRoute.addChildren([homeRoute, projectsRoute, newProjectRoute, issuesRoute, dashboardsRoute]),
    projectRoute.addChildren([
      projectIndexRoute,
      boardRoute,
      backlogRoute,
      timelineRoute,
      reportsRoute,
      settingsRoute,
      issueRoute,
    ]),
  ]),
])

// BLOCK 6: the router. defaultPreload 'intent': hovering or focusing a link starts its route's loaders.
// defaultPreloadStaleTime 0: the router always runs a preload, and TanStack Query decides whether the data
// is fresh enough to skip the request — one cache with one freshness rule, not two.
export function createAppRouter(queryClient: QueryClient, history?: RouterHistory) {
  return createRouter({
    routeTree,
    context: { queryClient },
    defaultPreload: 'intent',
    defaultPreloadStaleTime: 0,
    scrollRestoration: true,
    ...(history ? { history } : {}),
  })
}

// Module augmentation: adds our router's type to the library's `Register` interface. From then on every
// <Link to>, useSearch and navigate() in the app is checked against this route tree — a link to a route
// that doesn't exist, or a missing $projectKey param, is a compile error.
declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>
  }
}

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
import { backlogQuery } from '@/features/backlog/queries.ts'
import { boardQuery } from '@/features/board/queries.ts'
import { activityQuery, statsQuery, workQuery, workTabs } from '@/features/home/queries.ts'
import { issueQuery, transitionsQuery } from '@/features/issue/queries.ts'
import { projectQuery, projectsQuery } from '@/features/projects/queries.ts'
import {
  breakdownQuery,
  burndownQuery,
  sprintReportQuery,
  velocityQuery,
} from '@/features/reports/queries.ts'
import { filtersQuery } from '@/features/search/queries.ts'
import { membersQuery, workflowQuery } from '@/features/settings/queries.ts'
import { timelineQuery } from '@/features/timeline/queries.ts'
import { isApiError } from '@/shared/api/errors.ts'
import { safeNext } from '@/shared/lib/urls.ts'
import { AppLayout, RootLayout, RouteError } from './layouts.tsx'
import { ProjectShell } from './ProjectShell.tsx'

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

// Issues & filters (phase 17): everything that decides the results is in the URL. Basic mode keeps the pills as
// arrays (?types=bug&types=task), query mode the TQL text (?q=…). The results load in the page (useSearch), not
// here: a query with a typo must show its error under the editor, not the route's error screen.
// one value or several: the router writes arrays as JSON, but a hand-typed ?types=bug arrives as a plain string
const list = z
  .union([z.array(z.string()), z.string().transform((value) => [value])])
  .optional()
  .catch(undefined)
const issuesSearch = z.object({
  mode: z.enum(['basic', 'query']).optional().catch(undefined),
  q: z.string().optional().catch(undefined),
  filter: z.string().optional().catch(undefined),
  // plural names: the backlog's ?type= is a single enum, and search params of all routes share one namespace
  projects: list,
  types: list,
  statuses: list,
  assignees: list,
  priorities: list,
  text: z.string().optional().catch(undefined),
  sort: z.string().optional().catch(undefined),
})
export type IssuesSearch = z.infer<typeof issuesSearch>

const issuesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: 'issues',
  validateSearch: issuesSearch,
  loader: ({ context: { queryClient } }) =>
    Promise.all([
      queryClient.query({ ...filtersQuery(), staleTime: 'static' }),
      queryClient.query({ ...projectsQuery(), staleTime: 'static' }),
    ]),
  component: lazyRouteComponent(() => import('@/features/search/IssuesPage.tsx'), 'IssuesPage'),
})

const dashboardsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: 'dashboards',
  loader: ({ context: { queryClient } }) => queryClient.query({ ...projectsQuery(), staleTime: 'static' }),
  component: lazyRouteComponent(() => import('@/features/reports/DashboardsPage.tsx'), 'DashboardsPage'),
})

// BLOCK 5: project routes — the shell with the project sidebar and the project's live stream
const projectRoute = createRoute({
  getParentRoute: () => authedRoute,
  path: 'p/$projectKey',
  loader: ({ context: { queryClient }, params }) =>
    queryClient.query({ ...projectQuery(params.projectKey), staleTime: 'static' }),
  component: ProjectShell,
})

const projectIndexRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: '/',
  beforeLoad: ({ params }) => {
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- the router's redirect is thrown by design
    throw redirect({ to: '/p/$projectKey/board', params })
  },
})

/**
 * The side panel (phase 15): `?issue=MAR-12` on the board or backlog. Its data loads with the page's, so the
 * panel slides in already filled; hovering a card's link preloads it (defaultPreload: 'intent').
 */
function loadIssue(queryClient: QueryClient, issueKey: string | undefined) {
  return issueKey === undefined
    ? undefined
    : Promise.all([
        queryClient.query({ ...issueQuery(issueKey), staleTime: 'static' }),
        queryClient.query({ ...transitionsQuery(issueKey), staleTime: 'static' }),
      ])
}

// one route each (not a .map over a list): each keeps its own literal path, so its params are typed exactly —
// a link to /p/$projectKey/board needs projectKey and nothing else
// The board's filters live in the URL (shareable, survive reload). Only `recent` is sent to the api, so it is
// the only one in loaderDeps: changing the text filter re-renders, it doesn't reload.
const boardSearch = z.object({
  q: z.string().optional().catch(undefined),
  mine: z.boolean().optional().catch(undefined),
  bugs: z.boolean().optional().catch(undefined),
  recent: z.boolean().optional().catch(undefined),
  assignee: z.array(z.string()).optional().catch(undefined),
  group: z.enum(['none', 'assignee', 'epic']).optional().catch(undefined),
  issue: z.string().optional().catch(undefined),
})
export type BoardSearch = z.infer<typeof boardSearch>

const boardRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'board',
  validateSearch: boardSearch,
  loaderDeps: ({ search }) => ({ recent: search.recent ?? false, issue: search.issue }),
  loader: ({ context: { queryClient }, params, deps }) =>
    Promise.all([
      queryClient.query({ ...boardQuery(params.projectKey, 'active', deps.recent), staleTime: 'static' }),
      loadIssue(queryClient, deps.issue),
    ]),
  component: lazyRouteComponent(() => import('@/features/board/BoardPage.tsx'), 'BoardPage'),
})

const backlogSearch = z.object({
  q: z.string().optional().catch(undefined),
  type: z.enum(['story', 'bug', 'task']).optional().catch(undefined),
  epic: z.string().optional().catch(undefined),
  issue: z.string().optional().catch(undefined),
})
export type BacklogSearch = z.infer<typeof backlogSearch>

const backlogRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'backlog',
  validateSearch: backlogSearch,
  loaderDeps: ({ search }) => ({ issue: search.issue }),
  loader: ({ context: { queryClient }, params, deps }) =>
    Promise.all([
      queryClient.query({ ...backlogQuery(params.projectKey), staleTime: 'static' }),
      loadIssue(queryClient, deps.issue),
    ]),
  component: lazyRouteComponent(() => import('@/features/backlog/BacklogPage.tsx'), 'BacklogPage'),
})
const timelineRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'timeline',
  loader: ({ context: { queryClient }, params }) =>
    queryClient.query({ ...timelineQuery(params.projectKey), staleTime: 'static' }),
  component: lazyRouteComponent(() => import('@/features/timeline/TimelinePage.tsx'), 'TimelinePage'),
})

// Reports: the cards load on their own (a Kanban project's burndown is a 404 the card shows), so the loader only
// starts the requests (errors swallowed: the cards show them) and the page renders while they run.
const reportsRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'reports',
  validateSearch: z.object({ sprint: z.string().optional().catch(undefined) }),
  loaderDeps: ({ search }) => ({ sprint: search.sprint }),
  loader: ({ context: { queryClient }, params, deps }) => {
    const ignore = () => undefined
    queryClient.query({ ...burndownQuery(params.projectKey, deps.sprint), staleTime: 'static' }).catch(ignore)
    queryClient.query({ ...velocityQuery(params.projectKey), staleTime: 'static' }).catch(ignore)
    queryClient
      .query({ ...sprintReportQuery(params.projectKey, deps.sprint), staleTime: 'static' })
      .catch(ignore)
    queryClient
      .query({ ...breakdownQuery(params.projectKey, deps.sprint), staleTime: 'static' })
      .catch(ignore)
  },
  component: lazyRouteComponent(() => import('@/features/reports/ReportsPage.tsx'), 'ReportsPage'),
})

const settingsRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'settings/$section',
  loader: ({ context: { queryClient }, params }) =>
    params.section === 'workflow'
      ? queryClient.query({ ...workflowQuery(params.projectKey), staleTime: 'static' })
      : params.section === 'people'
        ? queryClient.query({ ...membersQuery(params.projectKey), staleTime: 'static' })
        : undefined,
  component: lazyRouteComponent(() => import('@/features/settings/SettingsPage.tsx'), 'SettingsPage'),
})
const issueRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: 'issues/$issueKey',
  loader: ({ context: { queryClient }, params }) => loadIssue(queryClient, params.issueKey),
  component: lazyRouteComponent(() => import('@/features/issue/IssuePage.tsx'), 'IssuePage'),
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

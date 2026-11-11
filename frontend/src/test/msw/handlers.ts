import { http, HttpResponse } from 'msw/http'
import type {
  ActivityItem,
  BacklogView,
  BoardCard,
  BoardView,
  CreateIssueRequest,
  IssueDetails,
  IssueRow,
  Me,
  MoveRequest,
  ProjectDetails,
  ProjectSummary,
  Stats,
  StatusView,
} from '@/shared/api/types.ts'

// MSW intercepts fetch at the network layer: the app's real client, middleware and parsing all run, only the
// server is fake. The fixtures are typed with the generated schema types, so a backend change that breaks
// the frontend breaks these fixtures too.

export const ada: Me = {
  id: '0a48c856-2921-9312-ccd1-780e11c135b5',
  email: 'ada@trakr.dev',
  displayName: 'Ada Lovelace',
  initials: 'AL',
  avatarColor: 'violet',
  workspace: { id: 'w1', name: 'Trakr Demo', slug: 'trakr-demo', role: 'owner' },
}

export const stats: Stats = { assignedOpen: 12, dueThisWeek: 3, waitingReview: 2, unreadMentions: 0 }

function row(key: string, summary: string, statusName = 'To do', statusColor = 'todo'): IssueRow {
  return {
    id: `id-${key}`,
    key,
    projectKey: key.split('-')[0] ?? 'MAR',
    summary,
    type: 'story',
    priority: 'high',
    statusName,
    statusCategory: statusColor === 'done' ? 'done' : 'todo',
    statusColor,
    assigneeId: ada.id,
    at: '2026-11-09T08:00:00Z',
  }
}

export const work: Record<string, IssueRow[]> = {
  assigned: [row('MAR-28', 'Add pricing table'), row('MAR-32', 'Fix checkout flow', 'In review', 'review')],
  worked: [row('MAR-25', 'Polish hero banner', 'In progress', 'progress')],
  viewed: [],
  starred: [row('BUG-1', 'Crash when exporting CSV', 'Triage')],
}

export const activity: ActivityItem[] = [
  {
    id: 1,
    kind: 'transitioned',
    field: 'status',
    oldValue: { id: 's1', name: 'To do' },
    newValue: { id: 's2', name: 'Done' },
    actor: { id: ada.id, displayName: ada.displayName, initials: 'AL', avatarColor: 'violet' },
    issueKey: 'MAR-24',
    issueSummary: 'Test search box',
    occurredAt: '2026-11-09T07:00:00Z',
  },
]

export const projects: ProjectSummary[] = [
  {
    id: 'p1',
    key: 'MAR',
    name: 'Marketing site',
    template: 'scrum',
    access: 'limited',
    color: 'pink',
    lead: { id: ada.id, displayName: ada.displayName, initials: 'AL', avatarColor: 'violet' },
    starred: false,
    openIssues: 123,
    progressPct: 18,
    activeSprint: 'MAR Sprint 2',
    lastVisitedAt: '2026-11-08T10:00:00Z',
  },
  {
    id: 'p2',
    key: 'OPS',
    name: 'Operations',
    template: 'kanban',
    access: 'open',
    color: 'cyan',
    lead: { id: 'g', displayName: 'Grace Hopper', initials: 'GH', avatarColor: 'cyan' },
    starred: true,
    openIssues: 66,
    progressPct: 34,
    activeSprint: null,
    lastVisitedAt: null,
  },
]

export const marketing: ProjectDetails = {
  id: 'p1',
  workspaceId: 'w1',
  key: 'MAR',
  name: 'Marketing site',
  template: 'scrum',
  leadId: ada.id,
  access: 'limited',
  color: 'pink',
  version: 1,
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
  // To do -> In progress -> Done, To do -> Done, back from Done only to In progress (never to To do)
  statuses: [
    { id: 'st-todo', name: 'To do', category: 'todo', position: 0, color: 'todo' },
    { id: 'st-prog', name: 'In progress', category: 'in_progress', position: 1, color: 'progress' },
    { id: 'st-done', name: 'Done', category: 'done', position: 2, color: 'done' },
  ],
  transitions: [
    { id: 't1', fromStatusId: 'st-todo', toStatusId: 'st-prog' },
    { id: 't2', fromStatusId: 'st-prog', toStatusId: 'st-done' },
    { id: 't3', fromStatusId: 'st-prog', toStatusId: 'st-todo' },
    { id: 't4', fromStatusId: 'st-done', toStatusId: 'st-prog' },
    { id: 't5', fromStatusId: 'st-todo', toStatusId: 'st-done' },
  ],
  board: null,
  members: [
    { userId: ada.id, displayName: 'Ada Lovelace', initials: 'AL', avatarColor: 'violet', role: 'admin' },
    { userId: 'u-grace', displayName: 'Grace Hopper', initials: 'GH', avatarColor: 'cyan', role: 'member' },
  ],
  myRole: 'admin',
}

// BLOCK: the MAR board (phase 14) - the active sprint: two cards to do, one in progress (WIP limit 1), none done
export function card(
  key: string,
  statusId: string,
  rank: string,
  changes: Partial<BoardCard> = {},
): BoardCard {
  return {
    key,
    id: `id-${key}`,
    summary: `Summary of ${key}`,
    type: 'story',
    priority: 'medium',
    storyPoints: 3,
    assigneeId: null,
    epicId: null,
    flagged: false,
    rank,
    statusId,
    version: 1,
    ...changes,
  }
}

export function marBoard(): BoardView {
  return {
    board: { id: 'b1', name: 'MAR board', type: 'scrum' },
    sprint: {
      id: 's1',
      name: 'MAR Sprint 1',
      state: 'active',
      goal: 'Ship the landing page',
      startsOn: '2026-11-02',
      endsOn: '2026-11-16',
    },
    columns: [
      {
        id: 'col-todo',
        name: 'To do',
        statusIds: ['st-todo'],
        count: 2,
        overLimit: false,
        cards: [
          card('MAR-1', 'st-todo', 'H', { assigneeId: ada.id, epicId: 'e1' }),
          card('MAR-2', 'st-todo', 'P', { type: 'bug', flagged: true }),
        ],
      },
      {
        id: 'col-prog',
        name: 'In progress',
        statusIds: ['st-prog'],
        wipLimit: 1,
        count: 1,
        overLimit: false,
        cards: [card('MAR-3', 'st-prog', 'K', { assigneeId: 'u-grace' })],
      },
      { id: 'col-done', name: 'Done', statusIds: ['st-done'], count: 0, overLimit: false, cards: [] },
    ],
    epics: { e1: { key: 'MAR-10', name: 'Launch', color: 'cyan' } },
    people: {
      [ada.id]: { name: 'Ada Lovelace', initials: 'AL', color: 'violet' },
      'u-grace': { name: 'Grace Hopper', initials: 'GH', color: 'cyan' },
    },
  }
}

export function marBacklog(): BacklogView {
  const row = (key: string, rank: string, statusId = 'st-todo') => ({
    id: `id-${key}`,
    key,
    summary: `Summary of ${key}`,
    type: 'story',
    priority: 'medium',
    statusId,
    statusName: statusId === 'st-done' ? 'Done' : 'To do',
    statusCategory: statusId === 'st-done' ? 'done' : 'todo',
    assigneeId: null,
    storyPoints: 2,
    epicId: null,
    rank,
    flagged: false,
    version: 1,
  })
  const sprint = (id: string, name: string, state: 'active' | 'planned', position: number) => ({
    id,
    projectId: 'p1',
    name,
    state,
    position,
    version: 1,
  })
  return {
    activeSprint: {
      sprint: sprint('s1', 'MAR Sprint 1', 'active', 0),
      issues: [row('MAR-1', 'H'), row('MAR-3', 'K', 'st-done')],
      points: { todo: 2, inProgress: 0, done: 2 },
    },
    plannedSprints: [
      {
        sprint: sprint('s2', 'MAR Sprint 2', 'planned', 1),
        issues: [],
        points: { todo: 0, inProgress: 0, done: 0 },
      },
    ],
    backlog: {
      issues: [row('MAR-5', 'M'), row('MAR-6', 'N'), row('MAR-7', 'O')],
      points: { todo: 6, inProgress: 0, done: 0 },
    },
    epics: [
      {
        id: 'e1',
        key: 'MAR-10',
        summary: 'Launch',
        color: 'cyan',
        issueCount: 4,
        doneCount: 1,
        progressPct: 25,
      },
    ],
  }
}

/** What GET /issues/{key} (and a move) answers: the issue as the server now has it. */
export function issueDetails(key: string, changes: Partial<IssueDetails> = {}): IssueDetails {
  return {
    id: `id-${key}`,
    key,
    projectKey: 'MAR',
    type: 'story',
    summary: `Summary of ${key}`,
    status: { id: 'st-todo', name: 'To do', category: 'todo', color: 'todo' },
    priority: 'medium',
    reporter: { id: ada.id, displayName: 'Ada Lovelace', initials: 'AL', avatarColor: 'violet' },
    sprint: { id: 's1', name: 'MAR Sprint 1', state: 'active' },
    storyPoints: 3,
    rank: 'V',
    labels: [],
    flagged: false,
    timeTracking: { timeSpentMin: 0 },
    subtasks: [],
    links: [],
    watching: false,
    watcherCount: 0,
    starred: false,
    attachments: [],
    version: 2,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-09T00:00:00Z',
    ...changes,
  }
}

/** A move that succeeds: the requested status and sprint, the version bumped, and the server's own rank. */
export function moveAnswer(key: string, body: MoveRequest, rank = 'Z') {
  const status = marketing.statuses.find((s) => s.id === body.toStatusId)
  return {
    issue: issueDetails(key, {
      rank,
      version: body.expectedVersion + 1,
      ...(status
        ? { status: { id: status.id, name: status.name, category: status.category, color: status.color } }
        : {}),
      ...(body.toSprintId === undefined
        ? {}
        : {
            sprint:
              body.toSprintId === null ? null : { id: body.toSprintId, name: 'Sprint', state: 'planned' },
          }),
    }),
    warnings: [] as string[],
  }
}

/** The workflow statuses as /transitions answers them; from To do, MAR may go to In progress or Done. */
export function statusView(id: string): StatusView {
  const status = marketing.statuses.find((s) => s.id === id)
  if (!status) throw new Error(`no status ${id}`)
  return {
    id: status.id,
    name: status.name,
    category: status.category,
    color: status.color,
    position: status.position,
  }
}

export function allowedFrom(statusId: string): StatusView[] {
  return marketing.transitions
    .filter((t) => t.fromStatusId === statusId || t.fromStatusId == null)
    .map((t) => statusView(t.toStatusId))
}

/** POST /issues: the issue as created — MAR-99, version 0, from what was sent. */
export function createdIssue(body: CreateIssueRequest): IssueDetails {
  return issueDetails('MAR-99', {
    type: body.type,
    summary: body.summary,
    priority: body.priority ?? 'medium',
    storyPoints: body.storyPoints ?? null,
    labels: body.labels ?? [],
    sprint: null,
    rank: 'zz',
    version: 0,
  })
}

/** An RFC 9457 problem response, the way the api sends errors. */
export function problem(status: number, code: string, detail: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://trakr.dev/problems/${code.toLowerCase()}`, title: code, status, detail, code, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

// a signed-in session by default; tests that need a signed-out user override /me with server.use(...)
export const handlers = [
  http.get('/api/v1/me', () => HttpResponse.json(ada)),
  http.get('/api/v1/me/stats', () => HttpResponse.json(stats)),
  http.get('/api/v1/me/work', ({ request }) => {
    const tab = new URL(request.url).searchParams.get('tab') ?? 'assigned'
    return HttpResponse.json({ items: work[tab] ?? [], nextCursor: null })
  }),
  http.get('/api/v1/me/activity', () => HttpResponse.json(activity)),
  http.get('/api/v1/projects', () => HttpResponse.json(projects)),
  http.get('/api/v1/projects/MAR', () => HttpResponse.json(marketing)),
  http.get('/api/v1/projects/MAR/board', () => HttpResponse.json(marBoard())),
  http.get('/api/v1/projects/MAR/backlog', () => HttpResponse.json(marBacklog())),
  http.post('/api/v1/issues/:key/move', async ({ params, request }) =>
    HttpResponse.json(moveAnswer(String(params.key), (await request.json()) as MoveRequest)),
  ),
  // the issue page / side panel (phase 15)
  http.get('/api/v1/issues/:key', ({ params }) => HttpResponse.json(issueDetails(String(params.key)))),
  http.get('/api/v1/issues/:key/transitions', () => HttpResponse.json(allowedFrom('st-todo'))),
  http.get('/api/v1/issues/:key/comments', () => HttpResponse.json({ items: [], nextCursor: null })),
  http.get('/api/v1/issues/:key/history', () => HttpResponse.json({ items: [], nextCursor: null })),
  http.get('/api/v1/issues/:key/worklogs', () => HttpResponse.json([])),
  http.post('/api/v1/issues', async ({ request }) =>
    HttpResponse.json(createdIssue((await request.json()) as CreateIssueRequest), { status: 201 }),
  ),
  http.get('/api/v1/projects/key-suggestion', ({ request }) => {
    const name = new URL(request.url).searchParams.get('name') ?? ''
    const key = name
      .replace(/[^a-z]/gi, '')
      .slice(0, 3)
      .toUpperCase()
    return HttpResponse.json({ key, available: true })
  }),
  // the bell in the top nav (phase 17)
  http.get('/api/v1/notifications/unread-count', () => HttpResponse.json({ count: 2 })),
  http.get('/api/v1/notifications', () => HttpResponse.json({ items: [], nextCursor: null })),
  http.post('/api/v1/auth/login', () => HttpResponse.json(ada)),
  http.post('/api/v1/auth/signup', () => HttpResponse.json(ada, { status: 201 })),
]

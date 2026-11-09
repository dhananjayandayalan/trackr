import { http, HttpResponse } from 'msw/http'
import type { ActivityItem, IssueRow, Me, ProjectDetails, ProjectSummary, Stats } from '@/shared/api/types.ts'

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
  statuses: [],
  transitions: [],
  board: null,
  members: [],
  myRole: 'admin',
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
  http.get('/api/v1/projects/key-suggestion', ({ request }) => {
    const name = new URL(request.url).searchParams.get('name') ?? ''
    const key = name
      .replace(/[^a-z]/gi, '')
      .slice(0, 3)
      .toUpperCase()
    return HttpResponse.json({ key, available: true })
  }),
  http.post('/api/v1/auth/login', () => HttpResponse.json(ada)),
  http.post('/api/v1/auth/signup', () => HttpResponse.json(ada, { status: 201 })),
]

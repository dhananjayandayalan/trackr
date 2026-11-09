import { screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw/http'
import { afterEach, describe, expect, it } from 'vitest'
import { marBacklog } from '@/test/msw/handlers.ts'
import { server } from '@/test/msw/server.ts'
import { renderApp } from '@/test/render-app.tsx'

// fixture: active MAR Sprint 1 (MAR-1, MAR-3 done), planned MAR Sprint 2 (empty), backlog MAR-5..7, epic Launch
const section = (name: string) => screen.getByRole('region', { name })

describe('BacklogPage', () => {
  it('lists the active sprint, the planned ones and the backlog, with point totals', async () => {
    renderApp('/p/MAR/backlog')
    expect(await screen.findByRole('heading', { name: 'Backlog', level: 1 })).toBeInTheDocument()
    expect(within(section('MAR Sprint 1')).getByText('MAR-1')).toBeInTheDocument()
    expect(
      within(section('MAR Sprint 1')).getByLabelText('Points: 2 to do, 0 in progress, 2 done'),
    ).toBeInTheDocument()
    expect(within(section('MAR Sprint 2')).getByText(/Nothing here yet/)).toBeInTheDocument()
    expect(within(section('Backlog')).getAllByRole('listitem')).toHaveLength(3)
    // one sprint is active, so a planned one can't start
    expect(within(section('MAR Sprint 2')).getByRole('button', { name: 'Start sprint' })).toBeDisabled()
  })

  it('filters by epic from the epics panel', async () => {
    const { user, router } = renderApp('/p/MAR/backlog')
    await screen.findByRole('heading', { name: 'Backlog', level: 1 })
    await user.click(screen.getByRole('button', { name: /^Launch/ }))
    expect(router.state.location.search).toMatchObject({ epic: 'e1' })
    expect(within(section('Backlog')).getByText('No issues match the filters.')).toBeInTheDocument()
  })

  afterEach(() => {
    // undo the clientHeight below
    Reflect.deleteProperty(HTMLElement.prototype, 'clientHeight')
  })

  it('renders only part of a long list (virtualised past 50 rows)', async () => {
    // jsdom has no layout: every clientHeight is 0, and a 0px viewport shows no rows. Give the list's scroll
    // box the height a browser would (the virtualiser reads clientHeight).
    Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
      configurable: true,
      get(this: HTMLElement) {
        return this.hasAttribute('data-virtual') ? 600 : 0
      },
    })
    const view = marBacklog()
    const template = view.backlog.issues[0]
    if (!template) throw new Error('fixture has backlog rows')
    view.backlog.issues = Array.from({ length: 1000 }, (_, i) => ({
      ...template,
      id: `id-${i}`,
      key: `MAR-${1000 + i}`,
      rank: `a${String(i).padStart(4, '0')}V`,
    }))
    server.use(http.get('/api/v1/projects/MAR/backlog', () => HttpResponse.json(view)))
    renderApp('/p/MAR/backlog')
    await screen.findByRole('heading', { name: 'Backlog', level: 1 })

    const rows = within(section('Backlog')).getAllByRole('listitem')
    expect(within(section('Backlog')).getByRole('button', { expanded: true })).toHaveTextContent(
      '1000 issues',
    )
    expect(rows.length).toBeGreaterThan(0)
    expect(rows.length).toBeLessThan(60)
  })

  it('starts a sprint from its dialog', async () => {
    const view = marBacklog()
    view.activeSprint = null
    let started: unknown
    server.use(
      http.get('/api/v1/projects/MAR/backlog', () => HttpResponse.json(view)),
      http.post('/api/v1/sprints/:id/start', async ({ request }) => {
        started = await request.json()
        return HttpResponse.json({ ...view.plannedSprints[0]?.sprint, state: 'active' })
      }),
    )
    const { user } = renderApp('/p/MAR/backlog')
    await screen.findByRole('heading', { name: 'Backlog', level: 1 })

    await user.click(within(section('MAR Sprint 2')).getByRole('button', { name: 'Start sprint' }))
    const dialog = await screen.findByRole('dialog', { name: 'Start MAR Sprint 2' })
    const goal = within(dialog).getByLabelText('Goal')
    await user.type(goal, 'Launch the page')
    await user.click(within(dialog).getByRole('button', { name: 'Start sprint' }))

    await waitFor(() => {
      expect(started).toMatchObject({ name: 'MAR Sprint 2', goal: 'Launch the page' })
    })
    expect(started).toHaveProperty('startsOn', expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/))
    expect(await screen.findByText('MAR Sprint 2 started.')).toBeInTheDocument()
  })
})

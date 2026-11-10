import { screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw/http'
import { describe, expect, it } from 'vitest'
import { allowedFrom, issueDetails, statusView } from '@/test/msw/handlers.ts'
import { server } from '@/test/msw/server.ts'
import { renderApp } from '@/test/render-app.tsx'

// The full issue page through the real router and cache; MSW is the api. MAR-1 is in To do; the workflow
// allows To do → In progress and To do → Done, but nothing → To do from To do itself.
describe('StatusButton', () => {
  const menuOf = (trigger: HTMLElement) => {
    // jsdom has no Popover API: the menu is found the way the browser finds it, through popovertarget
    const menu = document.getElementById(trigger.getAttribute('popovertarget') ?? '')
    if (!menu) throw new Error('the status button points at its menu')
    return menu
  }

  it('lists only the transitions the workflow allows from the current status', async () => {
    renderApp('/p/MAR/issues/MAR-1')
    const trigger = await screen.findByRole('button', { name: 'Status: To do. Change status' })
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu')

    const menu = menuOf(trigger)
    await waitFor(() => {
      expect(
        within(menu)
          .getAllByRole('menuitem', { hidden: true })
          .map((item) => item.textContent),
      ).toEqual(['In progress', 'Done'])
    })
  })

  it('transitions optimistically with the version it has, then keeps the server answer', async () => {
    const sent: unknown[] = []
    server.use(
      http.post('/api/v1/issues/:key/transition', async ({ request }) => {
        const body = (await request.json()) as { toStatusId: string; expectedVersion: number }
        sent.push(body)
        const to = statusView(body.toStatusId)
        return HttpResponse.json(
          issueDetails('MAR-1', {
            status: { id: to.id, name: to.name, category: to.category, color: to.color },
            version: body.expectedVersion + 1,
          }),
        )
      }),
      // from In progress the workflow allows other moves: the menu follows
      http.get('/api/v1/issues/:key/transitions', () =>
        HttpResponse.json(sent.length > 0 ? allowedFrom('st-prog') : allowedFrom('st-todo')),
      ),
    )
    const { user } = renderApp('/p/MAR/issues/MAR-1')
    const trigger = await screen.findByRole('button', { name: 'Status: To do. Change status' })
    const menu = menuOf(trigger)
    await user.click(await within(menu).findByText('In progress'))

    expect(
      await screen.findByRole('button', { name: 'Status: In progress. Change status' }),
    ).toBeInTheDocument()
    expect(sent).toEqual([{ toStatusId: 'st-prog', expectedVersion: 2 }])
    const next = menuOf(screen.getByRole('button', { name: 'Status: In progress. Change status' }))
    await waitFor(() => {
      expect(
        within(next)
          .getAllByRole('menuitem', { hidden: true })
          .map((item) => item.textContent),
      ).toEqual(['Done', 'To do'])
    })
  })

  it('puts the old status back when the workflow refuses', async () => {
    server.use(
      http.post('/api/v1/issues/:key/transition', () =>
        HttpResponse.json(
          { status: 409, code: 'TRANSITION_NOT_ALLOWED', detail: 'Not from here.' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    const { user } = renderApp('/p/MAR/issues/MAR-1')
    const trigger = await screen.findByRole('button', { name: 'Status: To do. Change status' })
    await user.click(await within(menuOf(trigger)).findByText('Done'))

    expect(await screen.findByText("Couldn't save: Not from here.")).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Status: To do. Change status' })).toBeInTheDocument()
  })
})

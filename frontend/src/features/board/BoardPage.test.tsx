import { screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw/http'
import { describe, expect, it } from 'vitest'
import type { MoveRequest } from '@/shared/api/types.ts'
import { marBoard, moveAnswer } from '@/test/msw/handlers.ts'
import { server } from '@/test/msw/server.ts'
import { renderApp } from '@/test/render-app.tsx'

// The board through the real router and cache, with MSW as the api (fixture: To do MAR-1 MAR-2, In progress
// MAR-3 with a WIP limit of 1, Done empty).
const column = (name: string) => screen.getByRole('list', { name })
const keysIn = (name: string) =>
  within(column(name))
    .queryAllByRole('article')
    .map((card) => card.getAttribute('aria-label')?.split(',')[0])

describe('BoardPage', () => {
  it('shows the active sprint: its columns, counts, WIP limits and cards', async () => {
    renderApp('/p/MAR/board')
    expect(await screen.findByRole('heading', { name: 'MAR Sprint 1' })).toBeInTheDocument()
    expect(screen.getByText('Ship the landing page')).toBeInTheDocument()
    expect(keysIn('To do')).toEqual(['MAR-1', 'MAR-2'])
    expect(keysIn('In progress')).toEqual(['MAR-3'])
    expect(keysIn('Done')).toEqual([])
    // MAR-2 is flagged; the card says so to a screen reader too
    expect(within(column('To do')).getByRole('article', { name: 'MAR-2, flagged' })).toBeInTheDocument()
  })

  it('filters in the browser, with the filter in the URL', async () => {
    const { user, router } = renderApp('/p/MAR/board')
    await screen.findByRole('heading', { name: 'MAR Sprint 1' })

    await user.click(screen.getByRole('button', { name: 'Bugs only' }))
    expect(screen.getByRole('button', { name: 'Bugs only' })).toHaveAttribute('aria-pressed', 'true')
    expect(keysIn('To do')).toEqual(['MAR-2'])
    expect(keysIn('In progress')).toEqual([])
    expect(router.state.location.search).toMatchObject({ bugs: true })

    await user.click(screen.getByRole('button', { name: 'Bugs only' }))
    await user.click(screen.getByRole('button', { name: 'Only my issues' }))
    expect(keysIn('To do')).toEqual(['MAR-1'])
  })

  it('moves a card from its menu — the keyboard way to drag — and shows the WIP limit broken', async () => {
    const sent: MoveRequest[] = []
    server.use(
      http.post('/api/v1/issues/:key/move', async ({ params, request }) => {
        const body = (await request.json()) as MoveRequest
        sent.push(body)
        return HttpResponse.json({
          ...moveAnswer(String(params.key), body, 'L'),
          warnings: ['WIP_LIMIT_EXCEEDED'],
        })
      }),
    )
    const { user } = renderApp('/p/MAR/board')
    await screen.findByRole('heading', { name: 'MAR Sprint 1' })

    // The trigger is a real button; its popovertarget names the menu. jsdom hides popovers but can't open them
    // (no Popover API), and a hidden element has no accessible name, so the menu is found through the button
    // — the same link the browser follows — and its items by text.
    const trigger = screen.getByRole('button', { name: 'Move MAR-1' })
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu')
    const menu = document.getElementById(trigger.getAttribute('popovertarget') ?? '')
    if (!menu) throw new Error('the trigger points at its menu')
    // the menu offers only what the workflow allows from To do: In progress and Done
    expect(
      within(menu)
        .getAllByRole('menuitem', { hidden: true })
        .map((item) => item.textContent),
    ).toEqual(['Top of column', 'Bottom of column', 'In progress', 'Done'])
    await user.click(within(menu).getByText('In progress'))

    await waitFor(() => {
      expect(keysIn('In progress')).toEqual(['MAR-3', 'MAR-1'])
    })
    expect(sent).toEqual([{ toStatusId: 'st-prog', beforeKey: null, afterKey: null, expectedVersion: 1 }])
    expect(await screen.findByText('The column is now over its WIP limit.')).toBeInTheDocument()
    expect(screen.getByText(', over the WIP limit')).toBeInTheDocument()
  })

  it('without an active sprint: points to the backlog', async () => {
    server.use(
      http.get('/api/v1/projects/MAR/board', () => HttpResponse.json({ ...marBoard(), sprint: null })),
    )
    renderApp('/p/MAR/board')
    expect(await screen.findByText('No active sprint')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Plan a sprint in the backlog' })).toHaveAttribute(
      'href',
      '/p/MAR/backlog',
    )
  })
})

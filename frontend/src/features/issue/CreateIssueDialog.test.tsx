import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw/http'
import { describe, expect, it } from 'vitest'
import { boardQuery } from '@/features/board/queries.ts'
import type { CreateIssueRequest } from '@/shared/api/types.ts'
import { createdIssue, problem } from '@/test/msw/handlers.ts'
import { server } from '@/test/msw/server.ts'
import { cached } from '@/test/cached.ts'
import { renderApp } from '@/test/render-app.tsx'

// The dialog in the real app shell: `c` opens it from the board; MSW is the api.
async function openDialog() {
  const app = renderApp('/p/MAR/board')
  await screen.findByRole('heading', { name: 'MAR Sprint 1' })
  // one keydown, as the browser sends it. (user.keyboard('c') keeps its own key state, and its next type()
  // call replays the c into whatever has focus — something a browser never does after a prevented keydown.)
  fireEvent.keyDown(document.body, { key: 'c' })
  const dialog = await screen.findByRole('dialog', { name: 'Create issue' })
  return { ...app, dialog: within(dialog) }
}

describe('CreateIssueDialog', () => {
  it('opens with c, in the project on screen, and needs a summary', async () => {
    let requests = 0
    server.use(
      http.post('/api/v1/issues', () => {
        requests++
        return HttpResponse.json({}, { status: 500 })
      }),
    )
    const { user, dialog } = await openDialog()
    expect(dialog.getByLabelText('Project')).toHaveValue('MAR')

    await user.click(dialog.getByRole('button', { name: 'Create' }))
    expect(await dialog.findByText('Enter a summary.')).toBeInTheDocument()
    expect(dialog.getByLabelText('Summary')).toHaveAttribute('aria-invalid', 'true')
    expect(requests).toBe(0)
  })

  it("shows the server's field errors on their fields and keeps what was typed", async () => {
    server.use(
      http.post('/api/v1/issues', () =>
        problem(400, 'VALIDATION_FAILED', 'The request has invalid fields.', {
          errors: [{ field: 'storyPoints', message: 'must be between 0 and 100' }],
        }),
      ),
    )
    const { user, dialog } = await openDialog()
    await user.type(dialog.getByLabelText('Summary'), 'Too big to estimate')
    await user.type(dialog.getByLabelText('Story points'), '500')
    await user.click(dialog.getByRole('button', { name: 'Create' }))

    expect(await dialog.findByText('must be between 0 and 100')).toBeInTheDocument()
    expect(dialog.getByLabelText('Story points')).toHaveAttribute('aria-invalid', 'true')
    expect(dialog.getByLabelText('Summary')).toHaveValue('Too big to estimate')
  })

  it('"Create another" keeps the dialog open with project and type, and the new issue joins the board', async () => {
    const sent: CreateIssueRequest[] = []
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        const body = (await request.json()) as CreateIssueRequest
        sent.push(body)
        // in the active sprint, so it belongs on the board
        return HttpResponse.json(
          { ...createdIssue(body), sprint: { id: 's1', name: 'MAR Sprint 1', state: 'active' } },
          { status: 201 },
        )
      }),
    )
    const { user, dialog, queryClient } = await openDialog()
    await user.click(dialog.getByRole('radio', { name: 'Bug' }))
    await user.click(dialog.getByRole('switch', { name: 'Create another' }))
    await user.type(dialog.getByLabelText('Summary'), 'Checkout button is grey')
    await user.click(dialog.getByRole('button', { name: 'Create' }))

    expect(await screen.findByText('MAR-99 created.')).toBeInTheDocument()
    expect(sent).toEqual([
      expect.objectContaining({
        projectKey: 'MAR',
        type: 'bug',
        summary: 'Checkout button is grey',
        priority: 'medium',
      }),
    ])
    // still open, ready for the next one: summary empty, project and type remembered
    await waitFor(() => {
      expect(dialog.getByLabelText('Summary')).toHaveValue('')
    })
    expect(dialog.getByLabelText('Project')).toHaveValue('MAR')
    expect(dialog.getByRole('radio', { name: 'Bug' })).toBeChecked()
    // inserted into the board's cache from the answer — no refetch of the board
    const board = cached(queryClient, boardQuery('MAR').queryKey)
    expect(board.columns[0]?.cards.map((card) => card.key)).toContain('MAR-99')
  })
})

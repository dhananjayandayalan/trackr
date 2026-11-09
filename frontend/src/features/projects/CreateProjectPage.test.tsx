import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw/http'
import { describe, expect, it } from 'vitest'
import type { CreateProjectRequest } from '@/shared/api/types.ts'
import { marketing, problem } from '@/test/msw/handlers.ts'
import { server } from '@/test/msw/server.ts'
import { renderApp } from '@/test/render-app.tsx'

describe('CreateProjectPage', () => {
  it('picks a template, suggests a key from the name, and creates the project', async () => {
    let sent: CreateProjectRequest | undefined
    server.use(
      http.post('/api/v1/projects', async ({ request }) => {
        sent = (await request.json()) as CreateProjectRequest
        return HttpResponse.json({ ...marketing, key: sent.key, name: sent.name }, { status: 201 })
      }),
      http.get('/api/v1/projects/MOB', () =>
        HttpResponse.json({ ...marketing, key: 'MOB', name: 'Mobile app' }),
      ),
    )
    const { router, user } = renderApp('/projects/new')

    const kanban = await screen.findByRole('radio', { name: /Kanban/ })
    await user.click(kanban)
    expect(kanban).toBeChecked()

    await user.type(screen.getByLabelText('Name'), 'Mobile app')
    // the suggestion arrives from the (fake) api once the deferred name settles
    await waitFor(() => {
      expect(screen.getByLabelText('Key')).toHaveValue('MOB')
    })

    await user.click(screen.getByRole('radio', { name: 'cyan' }))
    await user.click(screen.getByRole('button', { name: 'Create project' }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/p/MOB/board')
    })
    expect(sent).toMatchObject({
      name: 'Mobile app',
      key: 'MOB',
      template: 'kanban',
      color: 'cyan',
      access: 'limited',
    })
  })

  it('shows PROJECT_KEY_TAKEN on the key field and keeps what was typed', async () => {
    server.use(
      http.post('/api/v1/projects', () => problem(409, 'PROJECT_KEY_TAKEN', 'This value is already taken.')),
    )
    const { user } = renderApp('/projects/new')

    await user.type(await screen.findByLabelText('Name'), 'Marketing site')
    await user.type(screen.getByLabelText('Description'), 'The public website')
    const key = screen.getByLabelText('Key')
    await user.clear(key)
    await user.type(key, 'MAR')
    await user.click(screen.getByRole('button', { name: 'Create project' }))

    expect(
      await screen.findByText('MAR is already used in this workspace. Pick another key.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Key')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Key')).toHaveValue('MAR')
    expect(screen.getByLabelText('Description')).toHaveValue('The public website')
  })
})

import { screen, waitFor } from '@testing-library/react'
import { delay } from 'msw'
import { http, HttpResponse } from 'msw/http'
import { beforeEach, describe, expect, it } from 'vitest'
import { ada, problem } from '@/test/msw/handlers.ts'
import { server } from '@/test/msw/server.ts'
import { renderApp } from '@/test/render-app.tsx'

describe('LoginPage', () => {
  // signed out: /me answers 401 until someone signs in
  beforeEach(() => {
    let signedIn = false
    server.use(
      http.get('/api/v1/me', () =>
        signedIn ? HttpResponse.json(ada) : problem(401, 'UNAUTHENTICATED', 'Sign in to continue.'),
      ),
      http.post('/api/v1/auth/login', () => {
        signedIn = true
        return HttpResponse.json(ada)
      }),
    )
  })

  it('a signed-out visit to / lands on /login, remembering where it was going', async () => {
    const { router } = renderApp('/projects')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.search).toEqual({ next: '/projects' })
  })

  it('signing in goes to `next`', async () => {
    const { router, user } = renderApp('/login?next=%2Fprojects')
    await user.type(await screen.findByLabelText('Email'), 'ada@trakr.dev')
    await user.type(screen.getByLabelText('Password'), 'correct-horse-7')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/projects')
    })
  })

  it('shows the pending state while the request runs', async () => {
    server.use(
      http.post('/api/v1/auth/login', async () => {
        await delay(200)
        return problem(401, 'UNAUTHENTICATED', 'Wrong email or password.')
      }),
    )
    const { user } = renderApp('/login')
    await user.type(await screen.findByLabelText('Email'), 'ada@trakr.dev')
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('button', { name: 'Signing in…' })).toBeDisabled()
    // the server's message, and the email kept (a form action resets the form; the state hands it back)
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong email or password.')
    expect(screen.getByLabelText('Email')).toHaveValue('ada@trakr.dev')
  })

  it('checks the password rules before asking the server', async () => {
    const { user } = renderApp('/login?mode=signup')
    await user.type(await screen.findByLabelText('Name'), 'Alan Turing')
    await user.type(screen.getByLabelText('Email'), 'alan@example.com')
    await user.type(screen.getByLabelText('Password'), 'onlyletters10')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    const password = screen.getByLabelText('Password')
    expect(await screen.findByText('Password must contain a symbol.')).toBeInTheDocument()
    expect(password).toHaveAttribute('aria-invalid', 'true')
    expect(password).toHaveAccessibleDescription(/must contain a symbol/)
  })

  it("puts the server's field errors next to their fields", async () => {
    server.use(
      http.post('/api/v1/auth/signup', () =>
        problem(400, 'VALIDATION_FAILED', 'The request has invalid fields.', {
          errors: [{ field: 'email', message: 'must be a well-formed email address' }],
        }),
      ),
    )
    const { user } = renderApp('/login?mode=signup')
    await user.type(await screen.findByLabelText('Name'), 'Alan Turing')
    await user.type(screen.getByLabelText('Email'), 'alan@example.com')
    await user.type(screen.getByLabelText('Password'), 'enigma-1912!')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText('must be a well-formed email address')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Name')).toHaveValue('Alan Turing')
  })
})

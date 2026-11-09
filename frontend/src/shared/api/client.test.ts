import { http, HttpResponse } from 'msw/http'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { problem } from '@/test/msw/handlers.ts'
import { server } from '@/test/msw/server.ts'
import { api, resetUnauthenticated, setUnauthenticatedHandler, unwrap } from './client.ts'
import { ApiError } from './errors.ts'

describe('api client', () => {
  afterEach(() => {
    resetUnauthenticated()
  })

  it('echoes the XSRF-TOKEN cookie in X-XSRF-TOKEN on a POST, not on a GET', async () => {
    document.cookie = 'XSRF-TOKEN=token-123; path=/'
    const seen: (string | null)[] = []
    server.use(
      http.post('/api/v1/auth/login', ({ request }) => {
        seen.push(request.headers.get('X-XSRF-TOKEN'))
        return HttpResponse.json({
          id: 'x',
          email: 'a@b.c',
          displayName: 'A',
          initials: 'A',
          avatarColor: 'pink',
        })
      }),
      http.get('/api/v1/me/stats', ({ request }) => {
        seen.push(request.headers.get('X-XSRF-TOKEN'))
        return HttpResponse.json({ assignedOpen: 0, dueThisWeek: 0, waitingReview: 0, unreadMentions: 0 })
      }),
    )

    await unwrap(api.POST('/api/v1/auth/login', { body: { email: 'a@b.c', password: 'x', remember: false } }))
    await unwrap(api.GET('/api/v1/me/stats'))

    expect(seen).toEqual(['token-123', null])
  })

  it('turns a problem response into an ApiError with its code and field errors', async () => {
    server.use(
      http.post('/api/v1/projects', () =>
        problem(409, 'PROJECT_KEY_TAKEN', 'This value is already taken.', {
          errors: [{ field: 'key', message: 'taken' }],
        }),
      ),
    )

    const call = unwrap(
      api.POST('/api/v1/projects', {
        body: { name: 'X', key: 'MAR', template: 'scrum', access: 'open', color: 'pink' },
      }),
    )

    await expect(call).rejects.toBeInstanceOf(ApiError)
    await expect(call).rejects.toMatchObject({ status: 409, code: 'PROJECT_KEY_TAKEN' })
    await call.catch((error: unknown) => {
      expect((error as ApiError).fieldErrors).toEqual({ key: 'taken' })
    })
  })

  it('a body that is not a problem still becomes an ApiError', async () => {
    server.use(http.get('/api/v1/me/stats', () => new HttpResponse('Bad gateway', { status: 502 })))
    await expect(unwrap(api.GET('/api/v1/me/stats'))).rejects.toMatchObject({ status: 502, code: 'HTTP_502' })
  })

  it('a 401 tells the app once, however many requests fail with it', async () => {
    const onUnauthenticated = vi.fn()
    setUnauthenticatedHandler(onUnauthenticated)
    server.use(http.get('/api/v1/me/stats', () => problem(401, 'UNAUTHENTICATED', 'Sign in to continue.')))

    const results = await Promise.allSettled([
      unwrap(api.GET('/api/v1/me/stats')),
      unwrap(api.GET('/api/v1/me/stats')),
      unwrap(api.GET('/api/v1/me/stats')),
    ])

    expect(results.every((result) => result.status === 'rejected')).toBe(true)
    expect(onUnauthenticated).toHaveBeenCalledTimes(1)
  })

  it('a 401 from sign-in (wrong password) is just an error, not a redirect', async () => {
    const onUnauthenticated = vi.fn()
    setUnauthenticatedHandler(onUnauthenticated)
    server.use(
      http.post('/api/v1/auth/login', () => problem(401, 'UNAUTHENTICATED', 'Wrong email or password.')),
    )

    await expect(
      unwrap(api.POST('/api/v1/auth/login', { body: { email: 'a@b.c', password: 'nope', remember: false } })),
    ).rejects.toMatchObject({ code: 'UNAUTHENTICATED' })
    expect(onUnauthenticated).not.toHaveBeenCalled()
  })
})

import createClient, { type Middleware } from 'openapi-fetch'
import { ApiError } from './errors.ts'
import type { paths } from './schema'

// BLOCK 1: the typed client. `paths` is generated from the api's OpenAPI document (pnpm api:types): every
// path, parameter, body and response is checked at compile time — rename a field in a Java record,
// regenerate, and the compiler lists every place in the frontend that used the old name.
// Absolute base URL: the page's own origin (same-origin requests, cookies included by default). Relative
// URLs would also work in the browser, but not in Node, where the tests run.
// `fetch` is looked up on every call, not captured once: openapi-fetch would otherwise keep the fetch that
// existed when this module loaded, and anything that wraps fetch later (MSW in tests, monitoring) is bypassed.
export const api = createClient<paths>({
  baseUrl: globalThis.location.origin,
  fetch: (request) => globalThis.fetch(request),
})

// BLOCK 2: CSRF. Spring Security sets the XSRF-TOKEN cookie (readable by JS on purpose) and wants it back in
// a header on every state-changing request. Another site can make the browser send our cookies, but it
// can't read them, so it can't copy the token into a header. Read at request time, not once at startup:
// the token changes when the session does (sign-in, sign-out).
const csrf: Middleware = {
  onRequest({ request }) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      const token = readCookie('XSRF-TOKEN')
      if (token) {
        request.headers.set('X-XSRF-TOKEN', token)
      }
    }
    return request
  },
}

// BLOCK 3: errors. Any non-2xx response becomes a thrown ApiError, so callers only see data or an
// exception. A 401 outside the auth endpoints means the session ended while the app was open: tell the
// app once (it navigates to /login) — every request in flight fails at the same moment, one redirect is enough.
let onUnauthenticated: () => void = () => {
  globalThis.location.assign(`/login?next=${encodeURIComponent(globalThis.location.pathname)}`)
}
let redirecting = false

export function setUnauthenticatedHandler(handler: () => void) {
  onUnauthenticated = handler
  redirecting = false
}

/** The auth gate calls this once the user is signed in again, so a later expiry redirects again. */
export function resetUnauthenticated() {
  redirecting = false
}

const problems: Middleware = {
  async onResponse({ request, response }) {
    if (response.ok) {
      return response
    }
    const error = await ApiError.from(response)
    const path = new URL(request.url).pathname
    // /auth/*: a wrong password is a 401 too. /me: the auth gate asks it on purpose and handles the 401.
    if (
      response.status === 401 &&
      !path.startsWith('/api/v1/auth/') &&
      path !== '/api/v1/me' &&
      !redirecting
    ) {
      redirecting = true
      onUnauthenticated()
    }
    throw error
  },
}

api.use(csrf, problems)

// BLOCK 4: unwrap. openapi-fetch returns { data, error, response }; errors already threw in the middleware,
// so this only turns "no data" (204, or a bug) into an exception and keeps query functions one-liners:
//   queryFn: () => unwrap(api.GET('/api/v1/me'))
export async function unwrap<T>(
  call: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, response } = await call
  if (data === undefined) {
    throw new ApiError({ status: response.status, title: 'The response had no body' })
  }
  return data
}

/** For calls that answer 204 No Content. */
export async function done(call: Promise<{ response: Response }>): Promise<void> {
  await call
}

/**
 * A POST outside the OpenAPI document (Spring Security's own endpoints, e.g. /api/v1/auth/logout), with the
 * same CSRF header and error handling as the typed client.
 */
export async function postOutsideSchema(path: string): Promise<void> {
  const token = readCookie('XSRF-TOKEN')
  const response = await fetch(new URL(path, globalThis.location.origin), {
    method: 'POST',
    headers: token ? { 'X-XSRF-TOKEN': token } : {},
  })
  if (!response.ok) {
    throw await ApiError.from(response)
  }
}

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`
  const cookie = document.cookie.split('; ').find((part) => part.startsWith(prefix))
  return cookie === undefined ? undefined : decodeURIComponent(cookie.slice(prefix.length))
}

/**
 * Where to go after signing in. Only same-site paths: `?next=https://evil.example` (or `//evil.example`,
 * which browsers read as a host) must not turn the sign-in page into a redirect to someone else's site.
 */
export function safeNext(next: string | undefined): string {
  return next?.startsWith('/') && !next.startsWith('//') ? next : '/'
}

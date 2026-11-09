import { Link, Outlet, useRouter, type ErrorComponentProps } from '@tanstack/react-router'
import { lazy, Suspense } from 'react'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'
import { Toaster } from '@/shared/ui/Toast.tsx'
import { AppShell } from './AppShell.tsx'
import styles from './layouts.module.css'

// dev-only tools: in a build import.meta.env.DEV is false, so the import (and the devtools) disappear
const Devtools =
  import.meta.env.DEV && import.meta.env.MODE !== 'test'
    ? lazy(() => import('./Devtools.tsx').then((module) => ({ default: module.Devtools })))
    : () => null

/** Every page: the matched route, the toasts, and in dev the router and query devtools. */
export function RootLayout() {
  return (
    <>
      <Outlet />
      <Toaster />
      <Suspense>
        <Devtools />
      </Suspense>
    </>
  )
}

/** Home, Projects, Issues…: the shell without a project sidebar. */
export function AppLayout() {
  return (
    <AppShell>
      <Outlet />
    </AppShell>
  )
}

/** A screen that a later phase builds. */
export function ComingSoon({ title, phase }: { title: string; phase: number }) {
  return (
    <div className={styles.page}>
      <h1>{title}</h1>
      <EmptyState icon="bolt" title={`Coming in phase ${phase}`}>
        The route, loader and shell are in place; this screen is built in phase {phase}.
      </EmptyState>
    </div>
  )
}

/** What a route shows when its loader or component throws. 404 from the api reads as "not found". */
export function RouteError({ error, reset }: ErrorComponentProps) {
  const router = useRouter()
  const notFound = isApiError(error) && error.status === 404
  return (
    <div className={styles.page}>
      <EmptyState
        icon={notFound ? 'search' : 'close'}
        title={notFound ? 'Not found' : 'Something went wrong'}
        action={
          <div className={styles.actions}>
            {!notFound && (
              <Button
                variant="primary"
                onClick={() => {
                  reset()
                  void router.invalidate()
                }}
              >
                Try again
              </Button>
            )}
            <Link to="/">Back to your work</Link>
          </div>
        }
      >
        {notFound ? "It doesn't exist, or you don't have access to it." : messageOf(error)}
      </EmptyState>
    </div>
  )
}

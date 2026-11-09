import type { ReactNode } from 'react'
import { TopNav } from './TopNav.tsx'
import styles from './AppShell.module.css'

/**
 * The frame every signed-in screen sits in: top nav, optional project sidebar, main content (08-css-volt §6).
 * Layout routes render it with an <Outlet/> as children.
 */
export function AppShell({ sidebar, children }: { sidebar?: ReactNode; children: ReactNode }) {
  return (
    // BLOCK 1: two elements, because a container query styles the container's descendants, never the
    // container itself: .frame is the measured container, .root (inside it) is the grid that changes
    <div className={styles.frame}>
      <div className={styles.root} data-sidebar={sidebar ? true : undefined}>
        {/* BLOCK 2: keyboard users skip the nav in one Tab + Enter; visible only while focused */}
        <a className={styles.skip} href="#main">
          Skip to content
        </a>
        <TopNav />
        {sidebar}
        <main id="main" className={styles.main} tabIndex={-1}>
          {children}
        </main>
      </div>
    </div>
  )
}

import type { ReactNode } from 'react'
import { Sidebar } from './Sidebar.tsx'
import { TopNav } from './TopNav.tsx'
import styles from './AppShell.module.css'

/**
 * The frame every signed-in screen sits in: top nav, project sidebar, main content (08-css-volt §6).
 * Static for now; in phase 13 the router renders pages into it through <Outlet/>.
 */
export function AppShell({ children }: { children: ReactNode }) {
  return (
    // BLOCK 1: two elements, because a container query styles the container's descendants, never the
    // container itself: .frame is the measured container, .root (inside it) is the grid that changes
    <div className={styles.frame}>
      <div className={styles.root}>
        {/* BLOCK 2: keyboard users skip the nav in one Tab + Enter; visible only while focused */}
        <a className={styles.skip} href="#main">
          Skip to content
        </a>
        <TopNav />
        <Sidebar />
        <main id="main" className={styles.main} tabIndex={-1}>
          {children}
        </main>
      </div>
    </div>
  )
}

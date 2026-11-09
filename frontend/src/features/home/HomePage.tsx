import { useSuspenseQuery } from '@tanstack/react-query'
import { meQuery } from '@/features/auth/queries.ts'
import { ActivityFeed } from './ActivityFeed.tsx'
import { RecentProjects } from './RecentProjects.tsx'
import { StatTiles } from './StatTiles.tsx'
import { WorkTabs } from './WorkTabs.tsx'
import styles from './HomePage.module.css'

/**
 * "Your work" (00-product §3.2). The route's loader fetched stats, the current tab, projects and activity in
 * parallel before this rendered, so every useSuspenseQuery below reads the cache and nothing suspends.
 */
export function HomePage() {
  const { data: me } = useSuspenseQuery(meQuery())
  const firstName = me.displayName.split(' ')[0] ?? me.displayName

  return (
    <div className={styles.root}>
      <header className={styles.header}>
        <p className={styles.eyebrow}>Your work</p>
        <h1>Hi {firstName}, here's what's on your plate</h1>
      </header>
      <StatTiles />
      <div className={styles.columns}>
        <WorkTabs />
        <div className={styles.side}>
          <RecentProjects />
          <ActivityFeed />
        </div>
      </div>
    </div>
  )
}

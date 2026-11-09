import { useId } from 'react'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { Button } from '@/shared/ui/Button.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Kbd } from '@/shared/ui/Kbd.tsx'
import styles from './TopNav.module.css'

// static until phase 13 brings the router and the signed-in user
const links = [
  { href: '/', label: 'Your work', current: true },
  { href: '/projects', label: 'Projects', current: false },
  { href: '/issues', label: 'Issues', current: false },
  { href: '/reports', label: 'Dashboards', current: false },
]
const unread = 3

/** 00-product §3.13: Your work, Projects, Issues, Dashboards, Create, search, notifications, help, settings, profile. */
export function TopNav() {
  // a generated id: two shells on one page (the UI gallery) must not share an id
  const searchId = useId()
  return (
    <header className={styles.root}>
      {/* BLOCK 1: logo and main navigation; aria-current marks where you are, for screen readers and CSS */}
      <a className={styles.logo} href="/">
        <span className={styles.mark} aria-hidden="true">
          T
        </span>
        TRAKR
      </a>
      <nav aria-label="Main" className={styles.nav}>
        <ul role="list" className={styles.links}>
          {links.map((link) => (
            <li key={link.href}>
              <a className={styles.link} href={link.href} aria-current={link.current ? 'page' : undefined}>
                {link.label}
              </a>
            </li>
          ))}
        </ul>
      </nav>
      <Button variant="primary" icon="plus" className={styles.create}>
        Create
      </Button>

      {/* BLOCK 2: search — a real form with role search, so it is a landmark; "/" will focus it (phase 17) */}
      <form role="search" className={styles.search} onSubmit={(event) => event.preventDefault()}>
        <label htmlFor={searchId} className="visually-hidden">
          Search issues
        </label>
        <Icon name="search" size="sm" className={styles.searchIcon} />
        <input id={searchId} type="search" placeholder="Search" className={styles.searchInput} />
        <Kbd aria-label="Shortcut: slash">/</Kbd>
      </form>

      {/* BLOCK 3: icon buttons — each has a spoken name; the count is part of the bell's name */}
      <div className={styles.actions}>
        <span className={styles.bell}>
          <Button variant="ghost" icon="bell" aria-label={`Notifications, ${unread} unread`} />
          <span className={styles.badge} aria-hidden="true">
            {unread}
          </span>
        </span>
        <Button variant="ghost" icon="help" aria-label="Help" />
        <Button variant="ghost" icon="settings" aria-label="Settings" />
        <button type="button" className={styles.profile} aria-label="Your profile: Ada Lovelace">
          <Avatar name="Ada Lovelace" initials="AL" color="violet" />
        </button>
      </div>
    </header>
  )
}

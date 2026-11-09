import { useSuspenseQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useId } from 'react'
import { useLogout } from '@/features/auth/mutations.ts'
import { meQuery } from '@/features/auth/queries.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { Button } from '@/shared/ui/Button.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Kbd } from '@/shared/ui/Kbd.tsx'
import { showToast } from '@/shared/ui/Toast.tsx'
import styles from './TopNav.module.css'

/** 00-product §3.13: Your work, Projects, Issues, Dashboards, Create, search, notifications, help, settings, profile. */
export function TopNav() {
  // the auth gate cached /me before any of this renders, so this never suspends
  const { data: me } = useSuspenseQuery(meQuery())
  const searchId = useId()
  const menuId = useId()
  const logout = useLogout()
  const navigate = useNavigate()

  return (
    <header className={styles.root}>
      {/* BLOCK 1: logo and main navigation. <Link> renders a real <a href>, adds aria-current="page" when
          its route is active, and (defaultPreload: 'intent') starts the route's loader on hover or focus */}
      <Link className={styles.logo} to="/">
        <span className={styles.mark} aria-hidden="true">
          T
        </span>
        TRAKR
      </Link>
      <nav aria-label="Main" className={styles.nav}>
        <ul role="list" className={styles.links}>
          <li>
            <Link className={styles.link} to="/" activeOptions={{ exact: true, includeSearch: false }}>
              Your work
            </Link>
          </li>
          <li>
            <Link className={styles.link} to="/projects">
              Projects
            </Link>
          </li>
          <li>
            <Link className={styles.link} to="/issues">
              Issues
            </Link>
          </li>
          <li>
            <Link className={styles.link} to="/dashboards">
              Dashboards
            </Link>
          </li>
        </ul>
      </nav>
      <Button
        variant="primary"
        icon="plus"
        className={styles.create}
        onClick={() => showToast('Creating issues arrives in phase 15.')}
      >
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

      {/* BLOCK 3: icon buttons, then the profile menu. popoverTarget wires the button to the popover with no
          JavaScript of ours: click toggles it, Esc and a click outside close it (popover="auto"). */}
      <div className={styles.actions}>
        <Button variant="ghost" icon="bell" aria-label="Notifications" />
        <Button variant="ghost" icon="help" aria-label="Help" />
        <button
          type="button"
          className={styles.profile}
          popoverTarget={menuId}
          aria-label={`Account: ${me.displayName}`}
        >
          <Avatar name={me.displayName} initials={me.initials} color={avatarColor(me.avatarColor)} />
        </button>
        <div id={menuId} popover="auto" className={styles.menu}>
          <p className={styles.menuName}>{me.displayName}</p>
          <p className={styles.menuEmail}>{me.email}</p>
          {me.workspace && <p className={styles.menuEmail}>{me.workspace.name}</p>}
          <Button
            icon="logout"
            size="sm"
            className={styles.menuAction}
            disabled={logout.isPending}
            onClick={() => {
              logout.mutate(undefined, {
                onSettled: () => void navigate({ to: '/login' }),
              })
            }}
          >
            Sign out
          </Button>
        </div>
      </div>
    </header>
  )
}

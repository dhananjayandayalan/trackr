import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { AppShell } from './AppShell.tsx'

describe('AppShell', () => {
  it('has the landmarks a screen reader jumps between', () => {
    render(<AppShell>page</AppShell>)
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Project' })).toBeInTheDocument()
    expect(screen.getByRole('search')).toBeInTheDocument()
    expect(screen.getByRole('main')).toHaveTextContent('page')
  })

  it('marks where you are with aria-current', () => {
    render(<AppShell>page</AppShell>)
    expect(screen.getByRole('link', { name: 'Your work' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Board' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Backlog' })).not.toHaveAttribute('aria-current')
  })

  it('the drawer toggle reports and flips its state', async () => {
    const user = userEvent.setup()
    render(<AppShell>page</AppShell>)
    // jsdom has no layout, so the toggle exists here even though CSS shows it only on narrow screens
    const toggle = screen.getByRole('button', { name: /Board/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(toggle.closest('aside')).toHaveAttribute('data-open', 'true')
  })
})

import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '@/test/render-app.tsx'

describe('AppShell', () => {
  it('has the landmarks a screen reader jumps between, and marks where you are', async () => {
    renderApp('/projects')
    expect(await screen.findByRole('heading', { name: 'Projects' })).toBeInTheDocument()
    // the top <header> is the page's banner (the page's own <header> inside <main> isn't one, though jsdom's
    // role mapping counts it too, so it's found through the main navigation)
    expect(screen.getByRole('navigation', { name: 'Main' }).closest('header')).toBeInTheDocument()
    expect(screen.getByRole('search')).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Projects' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Your work' })).not.toHaveAttribute('aria-current')
  })

  it('inside a project: the sidebar with the project, its current page marked, and a drawer toggle', async () => {
    const { user } = renderApp('/p/MAR')
    // /p/MAR redirects to the board
    expect(await screen.findByRole('heading', { name: 'Board' })).toBeInTheDocument()
    expect(screen.getByText('Marketing site')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Board' })).toHaveAttribute('aria-current', 'page')

    // jsdom has no layout, so the toggle exists here even though CSS shows it only on narrow screens
    const toggle = screen.getByRole('button', { name: 'Menu' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })
})

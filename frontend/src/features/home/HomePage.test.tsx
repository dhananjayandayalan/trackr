import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '@/test/render-app.tsx'

describe('HomePage', () => {
  it('renders the stats, the assigned tab, recent projects and activity', async () => {
    renderApp('/')
    expect(await screen.findByRole('heading', { name: /Hi Ada/ })).toBeInTheDocument()

    expect(screen.getByText('Assigned to me', { selector: 'dt' }).nextElementSibling).toHaveTextContent('12')
    expect(screen.getByRole('tab', { name: 'Assigned to me' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('link', { name: /MAR-28.*Add pricing table/ })).toBeInTheDocument()
    // only visited projects count as recent
    const recent = screen.getByRole('region', { name: /Recent projects/ })
    expect(within(recent).getByText('Marketing site')).toBeInTheDocument()
    expect(within(recent).queryByText('Operations')).not.toBeInTheDocument()
    expect(screen.getByText(/moved MAR-24 to Done/)).toBeInTheDocument()
  })

  it('switching tabs writes ?tab= and shows that list', async () => {
    const { router, user } = renderApp('/')
    await user.click(await screen.findByRole('tab', { name: 'Starred' }))

    await waitFor(() => {
      expect(router.state.location.search).toEqual({ tab: 'starred' })
    })
    expect(await screen.findByRole('link', { name: /BUG-1.*Crash when exporting CSV/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Starred' })).toHaveAttribute('aria-selected', 'true')
  })

  it('arrow keys move between tabs', async () => {
    const { router, user } = renderApp('/?tab=worked')
    const worked = await screen.findByRole('tab', { name: 'Worked on' })
    worked.focus()
    await user.keyboard('{ArrowRight}')

    await waitFor(() => {
      expect(router.state.location.search).toEqual({ tab: 'viewed' })
    })
    expect(await screen.findByText('Issues you open show up here.')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Viewed' })).toHaveFocus()
  })
})

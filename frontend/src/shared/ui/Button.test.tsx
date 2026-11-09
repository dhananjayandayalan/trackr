import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Button } from './Button.tsx'

describe('Button', () => {
  it('is a button named by its text', () => {
    render(<Button>Create</Button>)
    const button = screen.getByRole('button', { name: 'Create' })
    // not "submit": a Button inside a form must not submit it by accident
    expect(button).toHaveAttribute('type', 'button')
  })

  it('sets variant and size as data attributes', () => {
    render(
      <Button variant="primary" size="lg">
        Save
      </Button>,
    )
    const button = screen.getByRole('button', { name: 'Save' })
    expect(button).toHaveAttribute('data-variant', 'primary')
    expect(button).toHaveAttribute('data-size', 'lg')
  })

  it('defaults to a medium secondary button', () => {
    render(<Button>Cancel</Button>)
    expect(screen.getByRole('button')).toHaveAttribute('data-variant', 'secondary')
    expect(screen.getByRole('button')).toHaveAttribute('data-size', 'md')
  })

  it('calls onClick, but not when disabled', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    const { rerender } = render(<Button onClick={onClick}>Go</Button>)
    await user.click(screen.getByRole('button'))
    expect(onClick).toHaveBeenCalledTimes(1)

    rerender(
      <Button onClick={onClick} disabled>
        Go
      </Button>,
    )
    await user.click(screen.getByRole('button'))
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('an icon-only button is named by aria-label, the icon itself is hidden', () => {
    render(<Button icon="bell" aria-label="Notifications" />)
    const button = screen.getByRole('button', { name: 'Notifications' })
    expect(button).toHaveAttribute('data-icon-only', 'true')
    expect(button.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  it('passes ref and other props through', () => {
    let element: HTMLButtonElement | null = null
    render(
      <Button
        ref={(node) => {
          element = node
        }}
        aria-pressed="true"
      >
        Only my issues
      </Button>,
    )
    expect(element).toBeInstanceOf(HTMLButtonElement)
    expect(screen.getByRole('button', { pressed: true })).toBeInTheDocument()
  })
})

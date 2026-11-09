import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Avatar } from './Avatar.tsx'
import { Kbd } from './Kbd.tsx'
import { Lozenge } from './Lozenge.tsx'
import { PriorityIcon } from './PriorityIcon.tsx'
import { TypeIcon } from './TypeIcon.tsx'

// The small display components: what matters is that each one has a spoken name, since colour and
// shape alone mean nothing to a screen reader.
describe('display components', () => {
  it('TypeIcon is an image named by the type', () => {
    render(<TypeIcon type="bug" />)
    expect(screen.getByRole('img', { name: 'Bug' })).toHaveAttribute('data-type', 'bug')
  })

  it('PriorityIcon is an image named by the priority', () => {
    render(<PriorityIcon priority="highest" />)
    expect(screen.getByRole('img', { name: 'Highest priority' })).toHaveAttribute('data-priority', 'highest')
  })

  it('Avatar shows initials and is named by the full name', () => {
    render(<Avatar name="Ada Lovelace" initials="AL" color="violet" />)
    const avatar = screen.getByRole('img', { name: 'Ada Lovelace' })
    expect(avatar).toHaveTextContent('AL')
    expect(avatar).toHaveAttribute('data-color', 'violet')
  })

  it('Lozenge is text with a tone', () => {
    render(<Lozenge tone="review">In review</Lozenge>)
    expect(screen.getByText('In review')).toHaveAttribute('data-tone', 'review')
  })

  it('Kbd renders a <kbd>', () => {
    render(<Kbd>/</Kbd>)
    expect(screen.getByText('/').tagName).toBe('KBD')
  })
})

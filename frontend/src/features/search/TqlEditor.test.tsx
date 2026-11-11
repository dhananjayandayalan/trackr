import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw/http'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { server } from '@/test/msw/server.ts'
import { TqlEditor } from './TqlEditor.tsx'
import type { TqlError } from './useSearch.ts'

// The api's autocomplete, faked: after "pro" it offers the field "project"; after "project " the operators.
function completions() {
  server.use(
    http.get('/api/v1/search/tql/complete', ({ request }) => {
      const url = new URL(request.url)
      const tql = url.searchParams.get('tql') ?? ''
      if (tql.endsWith(' ')) {
        return HttpResponse.json({
          from: tql.length,
          expected: [
            { kind: 'operator', text: '=' },
            { kind: 'operator', text: '!=' },
            { kind: 'operator', text: 'in' },
          ],
        })
      }
      const from = tql.lastIndexOf(' ') + 1
      return HttpResponse.json({
        from,
        expected: [
          { kind: 'field', text: 'project' },
          { kind: 'field', text: 'priority' },
        ],
      })
    }),
  )
}

function Harness({ onSubmit, error = null }: { onSubmit: (value: string) => void; error?: TqlError | null }) {
  const [value, setValue] = useState('')
  return <TqlEditor value={value} onChange={setValue} onSubmit={onSubmit} error={error} />
}

function renderEditor(props: { onSubmit?: (value: string) => void; error?: TqlError | null } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onSubmit = props.onSubmit ?? vi.fn()
  render(
    <QueryClientProvider client={queryClient}>
      <Harness onSubmit={onSubmit} error={props.error ?? null} />
    </QueryClientProvider>,
  )
  return { input: screen.getByRole('combobox', { name: 'Query' }), onSubmit, user: userEvent.setup() }
}

// the listbox is a popover: jsdom never opens popovers, so its options are "hidden" to the accessibility tree
const options = () => screen.queryAllByRole('option', { hidden: true })

describe('TqlEditor', () => {
  it('suggests what can come at the caret and fills it in with the keyboard', async () => {
    completions()
    const { input, user } = renderEditor()

    await user.type(input, 'pr')
    await waitFor(() => {
      expect(options().map((option) => option.textContent)).toEqual(['projectfield', 'priorityfield'])
    })
    expect(input).toHaveAttribute('aria-expanded', 'true')

    // ↓ highlights the first option (focus stays in the input; aria-activedescendant points at the option)
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    const first = options()[0]
    expect(first).toHaveAttribute('aria-selected', 'true')
    expect(input).toHaveAttribute('aria-activedescendant', first?.id)

    // ↓ ↑ wraps around, Enter takes the highlighted one and adds a space
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'ArrowUp' })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(input).toHaveValue('project ')

    await waitFor(() => {
      expect(options().map((option) => option.textContent)).toContain('=operator')
    })
  })

  it('closes on Escape, and runs the query on Enter when nothing is highlighted', async () => {
    completions()
    const { input, user, onSubmit } = renderEditor()

    await user.type(input, 'pr')
    await waitFor(() => {
      expect(input).toHaveAttribute('aria-expanded', 'true')
    })
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(input).toHaveAttribute('aria-expanded', 'false')

    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onSubmit).toHaveBeenCalledWith('pr')
  })

  it('underlines the word where the parser stopped and announces the error', () => {
    completions()
    const queryClient = new QueryClient()
    const error: TqlError = { message: "Unexpected 'AND' at 7", position: 7, expected: ['value'] }
    render(
      <QueryClientProvider client={queryClient}>
        <TqlEditor value="type = AND x" onChange={vi.fn()} onSubmit={vi.fn()} error={error} />
      </QueryClientProvider>,
    )

    const input = screen.getByRole('combobox', { name: 'Query' })
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription("Unexpected 'AND' at 7")
    expect(screen.getByRole('alert')).toHaveTextContent("Unexpected 'AND' at 7")
    // the mirror behind the input marks the word from the error position: "AND" in "type = AND x"
    expect(document.querySelector('mark')).toHaveTextContent(/^AND$/)
  })
})

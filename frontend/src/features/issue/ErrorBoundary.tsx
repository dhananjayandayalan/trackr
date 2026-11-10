import { Component, type ReactNode } from 'react'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'

/**
 * The panel's own error boundary: an issue that is gone (deleted, or no access) shows a message inside the
 * panel instead of taking down the board behind it. Error boundaries are still class components in React 19 —
 * there is no hook for "catch what my children throw while rendering".
 */
export class ErrorBoundary extends Component<
  { children: ReactNode; onClose: () => void },
  { error: unknown }
> {
  override state = { error: undefined as unknown }

  static getDerivedStateFromError(error: unknown) {
    return { error }
  }

  override render() {
    const { error } = this.state
    if (error === undefined) {
      return this.props.children
    }
    const notFound = isApiError(error) && error.status === 404
    return (
      <EmptyState
        icon={notFound ? 'search' : 'close'}
        title={notFound ? 'Issue not found' : 'Something went wrong'}
        action={<Button onClick={this.props.onClose}>Close</Button>}
      >
        {notFound ? "It doesn't exist any more, or you don't have access to it." : messageOf(error)}
      </EmptyState>
    )
  }
}

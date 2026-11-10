import { useSyncExternalStore } from 'react'

/**
 * Whether the Create Issue dialog is open, and with what defaults. A module-level store like the toasts:
 * the top nav's button, the `c` shortcut and an "Add issue" anywhere can open it without passing callbacks
 * through every layout in between; the one dialog (in AppShell) reads it.
 */
export interface CreateIssueDefaults {
  projectKey?: string | undefined
  sprintId?: string | null | undefined
  parentKey?: string | undefined
}

let state: { open: boolean; defaults: CreateIssueDefaults } = { open: false, defaults: {} }
const listeners = new Set<() => void>()

function emit() {
  for (const listener of listeners) listener()
}

export function openCreateIssue(defaults: CreateIssueDefaults = {}) {
  state = { open: true, defaults }
  emit()
}

export function closeCreateIssue() {
  state = { ...state, open: false }
  emit()
}

export function useCreateIssueState() {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => state,
  )
}

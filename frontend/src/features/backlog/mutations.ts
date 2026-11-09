import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { boardKeys } from '@/features/board/queries.ts'
import { hasPendingMove, MOVE_SCOPE } from '@/features/board/mutations.ts'
import { projectKeys, projectQuery } from '@/features/projects/queries.ts'
import { api, done, unwrap } from '@/shared/api/client.ts'
import { isApiError } from '@/shared/api/errors.ts'
import { fromDetails } from '@/shared/api/issue-state.ts'
import type { IssueTypeValue } from '@/shared/api/types.ts'
import { useToast } from '@/shared/ui/Toast.tsx'
import {
  applyBacklogIssue,
  applyBacklogMove,
  findRow,
  restoreRow,
  setRowVersion,
  type BacklogMove,
  type LiveBacklog,
  type StatusLookup,
} from './backlog-cache.ts'
import { backlogKeys } from './queries.ts'

/** Status names for rows built from an issue (the move's answer has them, events don't): the project's workflow. */
export function statusLookup(queryClient: QueryClient, projectKey: string): StatusLookup {
  const project = queryClient.getQueryData(projectQuery(projectKey).queryKey)
  return (statusId) => project?.statuses.find((status) => status.id === statusId)
}

// BLOCK 1: moving rows — the board's plan (features/board/mutations.ts) on the backlog cache, in the same
// scope, so a move made here and one made on the board can't overtake each other either.
export function useMoveBacklogIssue(projectKey: string) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const key = backlogKeys.project(projectKey)

  return useMutation({
    mutationKey: ['move', projectKey],
    scope: MOVE_SCOPE,
    mutationFn: (vars: BacklogMove & { version: number }) => {
      const view = queryClient.getQueryData<LiveBacklog>(key)
      const version = (view && findRow(view, vars.key)?.row.version) ?? vars.version
      return unwrap(
        api.POST('/api/v1/issues/{key}/move', {
          params: { path: { key: vars.key } },
          body: {
            toSprintId: vars.toSprintId,
            beforeKey: vars.beforeKey,
            afterKey: vars.afterKey,
            expectedVersion: version,
          },
        }),
      )
    },
    onMutate: async (vars) => {
      await queryClient.cancelQueries({ queryKey: key })
      const previous = queryClient.getQueryData<LiveBacklog>(key)
      queryClient.setQueryData<LiveBacklog>(key, (view) => view && applyBacklogMove(view, vars))
      return { previous }
    },
    onError: (error, vars, context) => {
      const previous = context?.previous
      if (previous) {
        queryClient.setQueryData<LiveBacklog>(key, (view) => view && restoreRow(view, previous, vars.key))
      }
      if (isApiError(error) && error.status === 412) {
        toast('Someone changed this issue a moment ago — the backlog was refreshed.', 'error')
        void queryClient.invalidateQueries({ queryKey: key })
      } else {
        toast(
          `Couldn't move ${vars.key}: ${isApiError(error) ? error.message : 'network error'} It was put back.`,
          'error',
        )
      }
    },
    onSuccess: (result, vars) => {
      const issue = fromDetails(result.issue)
      const later = hasPendingMove(queryClient, vars.key, vars)
      queryClient.setQueryData<LiveBacklog>(
        key,
        (view) =>
          view &&
          (later
            ? setRowVersion(view, vars.key, issue.version)
            : applyBacklogIssue(view, issue, statusLookup(queryClient, projectKey))),
      )
    },
  })
}

// BLOCK 2: sprints. These change several lists at once (completing one moves every open issue), so the
// answer is a refetch of what they touch, not a hand-made patch: the board, the backlog, and the
// projects list (it shows each project's active sprint).
function useSprintMutation<TVars, TData>(projectKey: string, mutationFn: (vars: TVars) => Promise<TData>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) }),
        queryClient.invalidateQueries({ queryKey: boardKeys.project(projectKey) }),
        queryClient.invalidateQueries({ queryKey: projectKeys.list() }),
      ]),
  })
}

export function useCreateSprint(projectKey: string) {
  return useSprintMutation(projectKey, () =>
    unwrap(api.POST('/api/v1/projects/{key}/sprints', { params: { path: { key: projectKey } }, body: {} })),
  )
}

export interface StartSprintVars {
  sprintId: string
  name: string
  goal: string | null
  startsOn: string
  endsOn: string
}

export function useStartSprint(projectKey: string) {
  return useSprintMutation(projectKey, ({ sprintId, ...body }: StartSprintVars) =>
    unwrap(api.POST('/api/v1/sprints/{id}/start', { params: { path: { id: sprintId } }, body })),
  )
}

export function useCompleteSprint(projectKey: string) {
  return useSprintMutation(
    projectKey,
    ({ sprintId, moveOpenIssuesTo }: { sprintId: string; moveOpenIssuesTo: string }) =>
      unwrap(
        api.POST('/api/v1/sprints/{id}/complete', {
          params: { path: { id: sprintId } },
          body: { moveOpenIssuesTo },
        }),
      ),
  )
}

export function useDeleteSprint(projectKey: string) {
  return useSprintMutation(projectKey, (sprintId: string) =>
    done(api.DELETE('/api/v1/sprints/{id}', { params: { path: { id: sprintId } } })),
  )
}

// BLOCK 3: inline create — the new issue joins its list from the answer (the issue.created event that follows
// is then no newer, and ignored)
export function useCreateBacklogIssue(projectKey: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (vars: { summary: string; type: IssueTypeValue; sprintId: string | null }) =>
      unwrap(api.POST('/api/v1/issues', { body: { projectKey, ...vars } })),
    onSuccess: (created) => {
      queryClient.setQueryData<LiveBacklog>(
        backlogKeys.project(projectKey),
        (view) =>
          view && applyBacklogIssue(view, fromDetails(created), statusLookup(queryClient, projectKey)),
      )
      void queryClient.invalidateQueries({ queryKey: boardKeys.project(projectKey) })
    },
  })
}

import { useMutation, useQueryClient, type QueryClient, type QueryKey } from '@tanstack/react-query'
import { api, unwrap } from '@/shared/api/client.ts'
import { isApiError } from '@/shared/api/errors.ts'
import { fromDetails } from '@/shared/api/issue-state.ts'
import { useToast } from '@/shared/ui/Toast.tsx'
import {
  applyIssue,
  applyMove,
  findCard,
  restoreCard,
  setVersion,
  type LiveBoard,
  type Move,
} from './board-cache.ts'
import { boardKeys } from './queries.ts'

export interface MoveVariables extends Move {
  /** the board view the drop happened on: the one cache entry patched optimistically */
  boardKey: QueryKey
  /** the version the card had when dragged; used only if the card has left the cache meanwhile */
  version: number
}

/** Mutations moving issues share one scope: they run one at a time, in the order they were made. */
export const MOVE_SCOPE = { id: 'board-moves' }

/**
 * Drag and drop's mutation (07-frontend §3, the plan followed exactly):
 * onMutate — cancel refetches, remember the board, apply the move at once (the card lands before the request
 *   leaves); onError — put that card back, tell the user; onSuccess — the server's answer (rank, version)
 *   replaces our guess. No onSettled refetch: live events keep everyone else's view fresh.
 *
 * Serialised with `scope`: two quick moves of the same card would otherwise be two requests in flight, and
 * nothing guarantees they arrive in order — the second could land first and then be overwritten by the first,
 * or fail with 412 because it carries the version from before the first move. In a scope, the second waits;
 * its mutationFn runs after the first has finished and reads the card's version from the cache *then*.
 * (onMutate still runs at once for each, so the screen never waits.)
 */
export function useMoveIssue(projectKey: string) {
  const queryClient = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationKey: ['move', projectKey],
    scope: MOVE_SCOPE,
    mutationFn: (vars: MoveVariables) => {
      const board = queryClient.getQueryData<LiveBoard>(vars.boardKey)
      const version = (board && findCard(board, vars.key)?.card.version) ?? vars.version
      return unwrap(
        api.POST('/api/v1/issues/{key}/move', {
          params: { path: { key: vars.key } },
          body: {
            toStatusId: vars.toStatusId,
            beforeKey: vars.beforeKey,
            afterKey: vars.afterKey,
            expectedVersion: version,
          },
        }),
      )
    },

    // BLOCK 1: optimistic — before the request
    onMutate: async (vars) => {
      // a refetch already in flight would land after our update and put the card back where it was
      await queryClient.cancelQueries({ queryKey: vars.boardKey })
      const previous = queryClient.getQueryData<LiveBoard>(vars.boardKey)
      queryClient.setQueryData<LiveBoard>(vars.boardKey, (board) => board && applyMove(board, vars))
      return { previous }
    },

    // BLOCK 2: failed — that card goes back; 412 means our copy is out of date, so refetch it
    onError: (error, vars, context) => {
      const previous = context?.previous
      if (previous) {
        queryClient.setQueryData<LiveBoard>(
          vars.boardKey,
          (board) => board && restoreCard(board, previous, vars.key),
        )
      }
      if (isApiError(error) && error.status === 412) {
        toast('Someone changed this issue a moment ago — the board was refreshed.', 'error')
        void queryClient.invalidateQueries({ queryKey: boardKeys.project(projectKey) })
      } else if (isApiError(error, 'TRANSITION_NOT_ALLOWED')) {
        toast("That move isn't allowed by the workflow.", 'error')
      } else {
        toast("Couldn't move the issue — it was put back.", 'error')
      }
    },

    // BLOCK 3: succeeded — the server is right about rank and version. If a later move of the same card is
    // still waiting its turn, take only the version: the card should stay where that later move put it.
    onSuccess: (result, vars) => {
      const laterMove = hasPendingMove(queryClient, vars.key, vars)
      const issue = fromDetails(result.issue)
      queryClient.setQueryData<LiveBoard>(
        vars.boardKey,
        (board) =>
          board && (laterMove ? setVersion(board, vars.key, issue.version) : applyIssue(board, issue)),
      )
      if (result.warnings.includes('WIP_LIMIT_EXCEEDED')) {
        toast('The column is now over its WIP limit.', 'info')
      }
    },
  })
}

/** Is a move of this issue (other than `except`) still pending? Used by onSuccess and by live updates. */
export function hasPendingMove(queryClient: QueryClient, key: string, except?: unknown): boolean {
  return queryClient
    .getMutationCache()
    .findAll({ status: 'pending' })
    .some((mutation) => {
      const variables = mutation.state.variables as { key?: string } | undefined
      return mutation.options.scope?.id === MOVE_SCOPE.id && variables !== except && variables?.key === key
    })
}

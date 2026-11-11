import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, done, unwrap } from '@/shared/api/client.ts'
import { messageOf } from '@/shared/api/errors.ts'
import type { FilterView } from '@/shared/api/types.ts'
import { useToast } from '@/shared/ui/Toast.tsx'
import { searchKeys } from './queries.ts'

// BLOCK 1: saved filters
export function useCreateFilter() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { name: string; tql: string; starred: boolean }) =>
      unwrap(api.POST('/api/v1/filters', { body })),
    onSuccess: (filter) => {
      queryClient.setQueryData<FilterView[]>(searchKeys.filters(), (list) => [...(list ?? []), filter])
    },
  })
}

/** Star / unstar: optimistic, the star fills at once and goes back if the server says no. */
export function useStarFilter() {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: ({ id, starred }: { id: string; starred: boolean }) =>
      unwrap(
        api.PATCH('/api/v1/filters/{id}', {
          params: { path: { id } },
          body: { starred },
          headers: { 'Content-Type': 'application/merge-patch+json' },
        }),
      ),
    onMutate: async ({ id, starred }) => {
      await queryClient.cancelQueries({ queryKey: searchKeys.filters() })
      const previous = queryClient.getQueryData<FilterView[]>(searchKeys.filters())
      queryClient.setQueryData<FilterView[]>(searchKeys.filters(), (list) =>
        list?.map((filter) => (filter.id === id ? { ...filter, starred } : filter)),
      )
      return { previous }
    },
    onError: (error, _vars, context) => {
      queryClient.setQueryData(searchKeys.filters(), context?.previous)
      toast(`Couldn't change the star: ${messageOf(error)}`, 'error')
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: searchKeys.filters() }),
  })
}

export function useDeleteFilter() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => done(api.DELETE('/api/v1/filters/{id}', { params: { path: { id } } })),
    onSuccess: (_result, id) => {
      queryClient.setQueryData<FilterView[]>(searchKeys.filters(), (list) => list?.filter((f) => f.id !== id))
    },
  })
}

// BLOCK 2: bulk actions over the selected rows (03-api §3.4). The answer is per key: some may fail (a transition
// the workflow doesn't allow), the rest still happen. Afterwards the result lists are stale: refetch them.
export type BulkAction = 'transition' | 'assign' | 'moveToSprint' | 'delete'

export interface BulkVars {
  keys: string[]
  action: BulkAction
  args?: { statusId?: string | null; assigneeId?: string | null; sprintId?: string | null }
}

export function useBulk() {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: ({ keys, action, args }: BulkVars) =>
      unwrap(api.POST('/api/v1/issues/bulk', { body: { keys, action, ...(args ? { args } : {}) } })),
    onSuccess: (results) => {
      const failed = results.filter((result) => !result.ok)
      const ok = results.length - failed.length
      if (failed.length === 0) {
        toast(`Updated ${String(ok)} issue${ok === 1 ? '' : 's'}.`, 'success')
      } else {
        const first = failed[0]
        toast(
          `${String(ok)} updated, ${String(failed.length)} not: ${first ? `${first.key} ${first.message ?? first.code ?? ''}` : ''}`,
          'error',
        )
      }
    },
    onError: (error) => {
      toast(`Bulk change failed: ${messageOf(error)}`, 'error')
    },
    // the index is updated by the workers a moment later; refetching right away may still show the old values,
    // so refetch once now and once more after the indexer had time to catch up
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: [...searchKeys.all, 'results'] })
      setTimeout(() => void queryClient.invalidateQueries({ queryKey: [...searchKeys.all, 'results'] }), 1500)
    },
  })
}

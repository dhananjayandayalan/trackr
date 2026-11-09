import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, done, unwrap } from '@/shared/api/client.ts'
import type { CreateProjectRequest, ProjectSummary } from '@/shared/api/types.ts'
import { showToast } from '@/shared/ui/Toast.tsx'
import { projectKeys } from './queries.ts'

export function useCreateProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (project: CreateProjectRequest) => unwrap(api.POST('/api/v1/projects', { body: project })),
    // the directory now has one more project; refetch it the next time it's shown
    onSuccess: () => queryClient.invalidateQueries({ queryKey: projectKeys.list() }),
  })
}

/**
 * Star / unstar, optimistically: the star flips the moment it's clicked. onMutate patches the cached list
 * (after cancelling a refetch that could overwrite the patch) and keeps the old list; onError puts it back.
 */
export function useStarProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ key, starred }: { key: string; starred: boolean }) => {
      const params = { params: { path: { key } } }
      return done(
        starred
          ? api.PUT('/api/v1/projects/{key}/star', params)
          : api.DELETE('/api/v1/projects/{key}/star', params),
      )
    },
    onMutate: async ({ key, starred }) => {
      await queryClient.cancelQueries({ queryKey: projectKeys.list() })
      const previous = queryClient.getQueryData<ProjectSummary[]>(projectKeys.list())
      queryClient.setQueryData<ProjectSummary[]>(projectKeys.list(), (projects) =>
        projects?.map((project) => (project.key === key ? { ...project, starred } : project)),
      )
      return { previous }
    },
    onError: (_error, { key }, context) => {
      queryClient.setQueryData(projectKeys.list(), context?.previous)
      showToast(`Couldn't update the star on ${key}.`, 'error')
    },
  })
}

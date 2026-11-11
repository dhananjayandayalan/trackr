import { queryOptions } from '@tanstack/react-query'
import { api } from '@/shared/api/client.ts'
import { ApiError } from '@/shared/api/errors.ts'
import type { MemberView, WorkflowView } from '@/shared/api/types.ts'

export const settingsKeys = {
  workflow: (projectKey: string) => ['projects', 'workflow', projectKey] as const,
  members: (projectKey: string) => ['projects', 'members', projectKey] as const,
}

/** The workflow and its ETag: the editor sends the ETag back in If-Match when it saves. */
export interface VersionedWorkflow {
  workflow: WorkflowView
  etag: string
}

// Unlike GET /projects/{key}, neither of these records a project visit: the issues page may read every
// project's statuses and people without moving them up "Recent projects".
export function workflowQuery(projectKey: string) {
  return queryOptions({
    queryKey: settingsKeys.workflow(projectKey),
    queryFn: async (): Promise<VersionedWorkflow> => {
      const { data, response } = await api.GET('/api/v1/projects/{key}/workflow', {
        params: { path: { key: projectKey } },
      })
      if (!data) {
        throw new ApiError({ status: response.status, title: 'The response had no body' })
      }
      return { workflow: data, etag: response.headers.get('ETag') ?? `"v${String(data.version)}"` }
    },
  })
}

export function membersQuery(projectKey: string) {
  return queryOptions({
    queryKey: settingsKeys.members(projectKey),
    queryFn: async (): Promise<MemberView[]> => {
      const { data } = await api.GET('/api/v1/projects/{key}/members', {
        params: { path: { key: projectKey } },
      })
      return data ?? []
    },
  })
}

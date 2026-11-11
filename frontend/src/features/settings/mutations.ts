import { useMutation, useQueryClient } from '@tanstack/react-query'
import { boardKeys } from '@/features/board/queries.ts'
import { projectKeys } from '@/features/projects/queries.ts'
import { api, done, unwrap } from '@/shared/api/client.ts'
import { ApiError, isApiError, messageOf } from '@/shared/api/errors.ts'
import type { MemberView, ProjectDetails, ProjectRole, ReplaceWorkflowRequest } from '@/shared/api/types.ts'
import { useToast } from '@/shared/ui/Toast.tsx'
import { settingsKeys, type VersionedWorkflow } from './queries.ts'

// BLOCK 1: the workflow — a whole replacement with If-Match (03-api §3.3). A 412 is not an error to toast: the
// editor shows "Someone changed the workflow" with a reload button, so it is returned to the caller as is.
export function useSaveWorkflow(projectKey: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({
      body,
      etag,
    }: {
      body: ReplaceWorkflowRequest
      etag: string
    }): Promise<VersionedWorkflow> => {
      const { data, response } = await api.PUT('/api/v1/projects/{key}/workflow', {
        params: { path: { key: projectKey }, header: { 'If-Match': etag } },
        body,
      })
      if (!data) {
        throw new ApiError({ status: response.status, title: 'The response had no body' })
      }
      return { workflow: data, etag: response.headers.get('ETag') ?? `"v${String(data.version)}"` }
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(settingsKeys.workflow(projectKey), saved)
      // statuses live in the project details and on the board too
      void queryClient.invalidateQueries({ queryKey: projectKeys.detail(projectKey) })
      void queryClient.invalidateQueries({ queryKey: boardKeys.project(projectKey) })
    },
  })
}

// BLOCK 2: project details (name, description, lead, access, colour) — a merge patch with the project's ETag
export function useUpdateProject(projectKey: string) {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: ({ patch, version }: { patch: Partial<ProjectDetails>; version: number }) =>
      unwrap(
        api.PATCH('/api/v1/projects/{key}', {
          params: { path: { key: projectKey }, header: { 'If-Match': `"v${String(version)}"` } },
          body: patch as never,
          headers: { 'Content-Type': 'application/merge-patch+json' },
        }),
      ),
    onSuccess: () => {
      toast('Project details saved.', 'success')
      void queryClient.invalidateQueries({ queryKey: projectKeys.all })
    },
    onError: (error) => {
      toast(
        isApiError(error) && error.status === 412
          ? 'Someone else changed the project meanwhile. Reloaded — apply your change again.'
          : `Couldn't save: ${messageOf(error)}`,
        'error',
      )
      void queryClient.invalidateQueries({ queryKey: projectKeys.detail(projectKey) })
    },
  })
}

// BLOCK 3: people — one request per change (PATCH a role, DELETE a member, POST an invite)
function useMembersChanged(projectKey: string) {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: settingsKeys.members(projectKey) })
    void queryClient.invalidateQueries({ queryKey: projectKeys.detail(projectKey) })
  }
}

export function useChangeRole(projectKey: string) {
  const queryClient = useQueryClient()
  const changed = useMembersChanged(projectKey)
  const toast = useToast()
  return useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: ProjectRole }) =>
      unwrap(
        api.PATCH('/api/v1/projects/{key}/members/{userId}', {
          params: { path: { key: projectKey, userId } },
          body: { role },
        }),
      ),
    onMutate: ({ userId, role }) => {
      const previous = queryClient.getQueryData<MemberView[]>(settingsKeys.members(projectKey))
      queryClient.setQueryData<MemberView[]>(settingsKeys.members(projectKey), (list) =>
        list?.map((member) => (member.userId === userId ? { ...member, role } : member)),
      )
      return { previous }
    },
    onError: (error, _vars, context) => {
      queryClient.setQueryData(settingsKeys.members(projectKey), context?.previous)
      toast(
        isApiError(error, 'LAST_ADMIN')
          ? 'A project needs at least one admin.'
          : `Couldn't change the role: ${messageOf(error)}`,
        'error',
      )
    },
    onSettled: changed,
  })
}

export function useRemoveMember(projectKey: string) {
  const changed = useMembersChanged(projectKey)
  const toast = useToast()
  return useMutation({
    mutationFn: (userId: string) =>
      done(
        api.DELETE('/api/v1/projects/{key}/members/{userId}', {
          params: { path: { key: projectKey, userId } },
        }),
      ),
    onError: (error) => {
      toast(
        isApiError(error, 'LAST_ADMIN')
          ? 'A project needs at least one admin.'
          : `Couldn't remove: ${messageOf(error)}`,
        'error',
      )
    },
    onSettled: changed,
  })
}

export function useAddMember(projectKey: string) {
  const changed = useMembersChanged(projectKey)
  const toast = useToast()
  return useMutation({
    mutationFn: async (body: { email: string; role: ProjectRole }) => {
      const { response } = await api.POST('/api/v1/projects/{key}/members', {
        params: { path: { key: projectKey } },
        body,
      })
      // 201: an existing user joined; 202: no account yet, an invitation email goes out
      return response.status
    },
    onSuccess: (status, { email }) => {
      toast(status === 202 ? `Invitation sent to ${email}.` : `${email} added.`, 'success')
    },
    onSettled: changed,
  })
}

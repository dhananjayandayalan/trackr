import { useMutation, useQueryClient, type InfiniteData, type QueryClient } from '@tanstack/react-query'
import { applyBacklogIssue, type LiveBacklog } from '@/features/backlog/backlog-cache.ts'
import { statusLookup } from '@/features/backlog/mutations.ts'
import { backlogKeys } from '@/features/backlog/queries.ts'
import { applyIssue, type LiveBoard } from '@/features/board/board-cache.ts'
import { boardKeys } from '@/features/board/queries.ts'
import { projectKeys } from '@/features/projects/queries.ts'
import { api, done, unwrap } from '@/shared/api/client.ts'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import { fromDetails } from '@/shared/api/issue-state.ts'
import type {
  Attachment,
  Comment,
  CommentPage,
  CreateIssueRequest,
  IssueDetails,
  IssuePatch,
  LinkType,
  StatusView,
} from '@/shared/api/types.ts'
import { useToast } from '@/shared/ui/Toast.tsx'
import { issueKeys } from './queries.ts'

// BLOCK 1: one place that knows every cache an issue appears in. The issue page has the full issue; the board
// and backlog have it as a card or a row. When the server tells us how an issue is now, all of them follow —
// that is how a status changed in the side panel shows on the board behind it without a refetch.
export function setIssue(queryClient: QueryClient, issue: IssueDetails) {
  queryClient.setQueryData(issueKeys.detail(issue.key), issue)
  const state = fromDetails(issue)
  queryClient.setQueriesData<LiveBoard>(
    { queryKey: boardKeys.project(issue.projectKey) },
    (board) => board && applyIssue(board, state),
  )
  queryClient.setQueriesData<LiveBacklog>(
    { queryKey: backlogKeys.project(issue.projectKey) },
    (view) => view && applyBacklogIssue(view, state, statusLookup(queryClient, issue.projectKey)),
  )
}

/** If-Match for the issue as we last saw it. */
function ifMatch(queryClient: QueryClient, key: string): string {
  const issue = queryClient.getQueryData<IssueDetails>(issueKeys.detail(key))
  return `"v${String(issue?.version ?? 0)}"`
}

// Edits of one issue run one at a time: each reads the version the previous one left (07-frontend §3)
const scopeOf = (key: string) => ({ id: `issue-${key}` })

/** What happens when an edit fails: put our copy back, and say why. 412 = someone else was faster (§4.10). */
function useEditFailure(key: string) {
  const queryClient = useQueryClient()
  const toast = useToast()
  return (error: Error, previous: IssueDetails | undefined) => {
    if (isApiError(error) && error.status === 412) {
      // the problem carries the issue as it is now: show that, and let the user apply their change again
      const current = error.problem.current as IssueDetails | undefined
      if (current) {
        setIssue(queryClient, current)
      } else {
        void queryClient.invalidateQueries({ queryKey: issueKeys.detail(key), exact: true })
      }
      toast(
        'Someone else changed this issue meanwhile — this is their version now. Apply yours again.',
        'error',
      )
      return
    }
    if (previous) {
      queryClient.setQueryData(issueKeys.detail(key), previous)
    }
    toast(`Couldn't save: ${messageOf(error)}`, 'error')
  }
}

// BLOCK 2: field edits — JSON merge patch with If-Match (03-api §1). Only the fields that changed are sent;
// `optimistic` is how the issue should look meanwhile (an assignee id alone can't show a name and an avatar).
export interface PatchVars {
  patch: IssuePatch
  optimistic?: Partial<IssueDetails>
}

export function usePatchIssue(key: string) {
  const queryClient = useQueryClient()
  const failed = useEditFailure(key)
  return useMutation({
    scope: scopeOf(key),
    mutationFn: ({ patch }: PatchVars) =>
      unwrap(
        api.PATCH('/api/v1/issues/{key}', {
          params: { path: { key }, header: { 'If-Match': ifMatch(queryClient, key) } },
          // the schema's record lists every field as present; a merge patch is any subset of them (IssuePatch)
          body: patch as Required<IssuePatch>,
          headers: { 'Content-Type': 'application/merge-patch+json' },
        }),
      ),
    onMutate: async ({ optimistic }) => {
      await queryClient.cancelQueries({ queryKey: issueKeys.detail(key), exact: true })
      const previous = queryClient.getQueryData<IssueDetails>(issueKeys.detail(key))
      if (previous && optimistic) {
        queryClient.setQueryData<IssueDetails>(issueKeys.detail(key), { ...previous, ...optimistic })
      }
      return { previous }
    },
    onError: (error, _vars, context) => {
      failed(error, context?.previous)
    },
    onSuccess: (issue) => {
      setIssue(queryClient, issue)
      void queryClient.invalidateQueries({ queryKey: issueKeys.history(key), exact: true })
    },
  })
}

// BLOCK 3: status — optimistic like the board's moves: the lozenge changes at once
export function useTransitionIssue(key: string) {
  const queryClient = useQueryClient()
  const failed = useEditFailure(key)
  return useMutation({
    scope: scopeOf(key),
    mutationFn: (to: StatusView) => {
      const issue = queryClient.getQueryData<IssueDetails>(issueKeys.detail(key))
      return unwrap(
        api.POST('/api/v1/issues/{key}/transition', {
          params: { path: { key } },
          body: { toStatusId: to.id, expectedVersion: issue?.version ?? 0 },
        }),
      )
    },
    onMutate: async (to) => {
      await queryClient.cancelQueries({ queryKey: issueKeys.detail(key), exact: true })
      const previous = queryClient.getQueryData<IssueDetails>(issueKeys.detail(key))
      if (previous) {
        queryClient.setQueryData<IssueDetails>(issueKeys.detail(key), {
          ...previous,
          status: { id: to.id, name: to.name, category: to.category, color: to.color },
        })
      }
      return { previous }
    },
    onError: (error, _to, context) => {
      failed(error, context?.previous)
    },
    onSuccess: (issue) => {
      setIssue(queryClient, issue)
      // from the new status, other moves are allowed
      void queryClient.invalidateQueries({ queryKey: issueKeys.transitions(key) })
      void queryClient.invalidateQueries({ queryKey: issueKeys.history(key), exact: true })
    },
  })
}

// BLOCK 4: personal marks — no version involved, so no If-Match and nothing to conflict with
export function useWatchIssue(key: string) {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (watch: boolean) =>
      done(
        watch
          ? api.PUT('/api/v1/issues/{key}/watch', { params: { path: { key } } })
          : api.DELETE('/api/v1/issues/{key}/watch', { params: { path: { key } } }),
      ),
    onMutate: (watch) => {
      const previous = queryClient.getQueryData<IssueDetails>(issueKeys.detail(key))
      if (previous && previous.watching !== watch) {
        queryClient.setQueryData<IssueDetails>(issueKeys.detail(key), {
          ...previous,
          watching: watch,
          watcherCount: previous.watcherCount + (watch ? 1 : -1),
        })
      }
      return { previous }
    },
    onError: (error, _watch, context) => {
      if (context?.previous) {
        queryClient.setQueryData(issueKeys.detail(key), context.previous)
      }
      toast(`Couldn't change watching: ${messageOf(error)}`, 'error')
    },
  })
}

// BLOCK 5: links, work log, subtasks — they change the issue's version (and more than one field), so the
// answer is a refetch of the issue rather than a hand-made patch
function useIssueRefresh(key: string) {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: issueKeys.detail(key), exact: true }),
      queryClient.invalidateQueries({ queryKey: issueKeys.history(key), exact: true }),
    ])
}

export function useAddLink(key: string) {
  const refresh = useIssueRefresh(key)
  return useMutation({
    mutationFn: (body: { targetKey: string; type: LinkType }) =>
      unwrap(api.POST('/api/v1/issues/{key}/links', { params: { path: { key } }, body })),
    onSuccess: refresh,
  })
}

export function useRemoveLink(key: string) {
  const refresh = useIssueRefresh(key)
  const toast = useToast()
  return useMutation({
    mutationFn: (linkId: string) =>
      done(api.DELETE('/api/v1/issue-links/{id}', { params: { path: { id: linkId } } })),
    onSuccess: refresh,
    onError: (error) => {
      toast(`Couldn't remove the link: ${messageOf(error)}`, 'error')
    },
  })
}

export function useLogWork(key: string) {
  const queryClient = useQueryClient()
  const refresh = useIssueRefresh(key)
  return useMutation({
    mutationFn: (body: { startedAt: string; minutes: number; note: string | null }) =>
      unwrap(api.POST('/api/v1/issues/{key}/worklogs', { params: { path: { key } }, body })),
    onSuccess: () =>
      Promise.all([refresh(), queryClient.invalidateQueries({ queryKey: issueKeys.worklogs(key) })]),
  })
}

// BLOCK 6: creating issues (the dialog, and "Add subtask"). The new issue joins the board and backlog from the
// answer — no refetch of either, and the issue.created event that follows is then no newer, and ignored.
export function useCreateIssue() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateIssueRequest) => unwrap(api.POST('/api/v1/issues', { body })),
    onSuccess: (issue, body) => {
      setIssue(queryClient, issue)
      void queryClient.invalidateQueries({ queryKey: projectKeys.list() })
      if (body.parentKey) {
        // the parent's subtasks (or the epic's children) changed
        void queryClient.invalidateQueries({ queryKey: issueKeys.detail(body.parentKey), exact: true })
      }
    },
  })
}

// BLOCK 7: comments. The first page is the newest: a new comment goes on top of it; edits and deletes replace
// or drop the one item, wherever it is in the pages loaded so far.
type CommentPages = InfiniteData<CommentPage, string | undefined>

function mapComments(pages: CommentPages, change: (items: Comment[], index: number) => Comment[]) {
  return {
    ...pages,
    pages: pages.pages.map((page, index) => ({ ...page, items: change(page.items, index) })),
  }
}

export function useAddComment(key: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (bodyHtml: string) =>
      unwrap(api.POST('/api/v1/issues/{key}/comments', { params: { path: { key } }, body: { bodyHtml } })),
    onSuccess: (comment) => {
      queryClient.setQueryData<CommentPages>(
        issueKeys.comments(key),
        (pages) =>
          pages &&
          mapComments(pages, (items, index) =>
            // the live event for our own comment may have refetched it in already
            index === 0 && !items.some((c) => c.id === comment.id) ? [comment, ...items] : items,
          ),
      )
    },
  })
}

export function useEditComment(key: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ comment, bodyHtml }: { comment: Comment; bodyHtml: string }) =>
      unwrap(
        api.PATCH('/api/v1/comments/{id}', {
          params: { path: { id: comment.id }, header: { 'If-Match': `"v${String(comment.version)}"` } },
          body: { bodyHtml },
        }),
      ),
    onSuccess: (edited) => {
      queryClient.setQueryData<CommentPages>(
        issueKeys.comments(key),
        (pages) =>
          pages && mapComments(pages, (items) => items.map((c) => (c.id === edited.id ? edited : c))),
      )
    },
    onError: (error) => {
      if (isApiError(error) && error.status === 412) {
        void queryClient.invalidateQueries({ queryKey: issueKeys.comments(key) })
      }
    },
  })
}

export function useDeleteComment(key: string) {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (comment: Comment) =>
      done(
        api.DELETE('/api/v1/comments/{id}', {
          params: { path: { id: comment.id }, header: { 'If-Match': `"v${String(comment.version)}"` } },
        }),
      ),
    onSuccess: (_result, comment) => {
      queryClient.setQueryData<CommentPages>(
        issueKeys.comments(key),
        (pages) => pages && mapComments(pages, (items) => items.filter((c) => c.id !== comment.id)),
      )
    },
    onError: (error) => {
      toast(`Couldn't delete the comment: ${messageOf(error)}`, 'error')
      void queryClient.invalidateQueries({ queryKey: issueKeys.comments(key) })
    },
  })
}

// BLOCK 8: attachments (uploads are useUpload.ts)
export function addAttachment(queryClient: QueryClient, key: string, attachment: Attachment) {
  queryClient.setQueryData<IssueDetails>(
    issueKeys.detail(key),
    (issue) =>
      issue && {
        ...issue,
        attachments: issue.attachments.some((a) => a.id === attachment.id)
          ? issue.attachments
          : [...issue.attachments, attachment],
      },
  )
}

export function useDeleteAttachment(key: string) {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (attachment: Attachment) =>
      done(api.DELETE('/api/v1/attachments/{id}', { params: { path: { id: attachment.id } } })),
    onSuccess: (_result, attachment) => {
      queryClient.setQueryData<IssueDetails>(
        issueKeys.detail(key),
        (issue) =>
          issue && { ...issue, attachments: issue.attachments.filter((a) => a.id !== attachment.id) },
      )
    },
    onError: (error) => {
      toast(`Couldn't remove the file: ${messageOf(error)}`, 'error')
    },
  })
}

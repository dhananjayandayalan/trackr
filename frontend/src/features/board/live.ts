import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { z } from 'zod'
import { applyBacklogIssue, removeBacklogIssue, type LiveBacklog } from '@/features/backlog/backlog-cache.ts'
import { statusLookup } from '@/features/backlog/mutations.ts'
import { backlogKeys } from '@/features/backlog/queries.ts'
import { issueKeys } from '@/features/issue/queries.ts'
import { projectKeys } from '@/features/projects/queries.ts'
import { fromSnapshot, issueSnapshotSchema, type IssueState } from '@/shared/api/issue-state.ts'
import type { IssueDetails } from '@/shared/api/types.ts'
import { openStream, type StreamHandlers, type StreamStatus } from '@/shared/api/sse.ts'
import { applyRemoteIssue, removeIssue, type LiveBoard } from './board-cache.ts'
import { hasPendingMove } from './mutations.ts'
import { boardKeys } from './queries.ts'

// BLOCK 1: what the wire may say. Parsed, never trusted: an event that doesn't match is ignored.
const issueEvent = z.looseObject({ sequence: z.number(), snapshot: issueSnapshotSchema })
const deletedEvent = z.looseObject({ key: z.string(), sequence: z.number() })
const presenceEvent = z.object({ userIds: z.array(z.string()) })
const commentEvent = z.looseObject({ issueKey: z.string() })

export interface ProjectLive {
  status: StreamStatus
  /** user ids with this project open right now (03-api §4 `presence`) */
  viewers: string[]
}

// BLOCK 2: the patches — one place that knows which caches an issue lives in
function patchIssue(queryClient: QueryClient, projectKey: string, issue: IssueState) {
  // a move of ours is still on its way: its answer (or its 412) will set the card right; an event now could
  // only move the card back under the user's cursor
  if (hasPendingMove(queryClient, issue.key)) {
    return
  }
  const now = Date.now()
  queryClient.setQueriesData<LiveBoard>(
    { queryKey: boardKeys.project(projectKey) },
    (board) => board && applyRemoteIssue(board, issue, now),
  )
  queryClient.setQueriesData<LiveBacklog>(
    { queryKey: backlogKeys.project(projectKey) },
    (view) => view && applyBacklogIssue(view, issue, statusLookup(queryClient, projectKey), { flash: now }),
  )
  refreshOpenIssue(queryClient, issue.key, issue.version)
}

/**
 * An issue page or panel that is open (its detail cached) and older than the event: refetch it. The event's
 * snapshot has the issue's own columns but not what the page shows around them (names, subtasks, links), so
 * a refetch is simpler than a patch. Our own edits arrive here too, already applied: same version, skipped.
 */
function refreshOpenIssue(queryClient: QueryClient, key: string, version: number) {
  const cached = queryClient.getQueryData<IssueDetails>(issueKeys.detail(key))
  if (cached && cached.version < version) {
    void queryClient.invalidateQueries({ queryKey: issueKeys.detail(key) })
  }
}

function dropIssue(queryClient: QueryClient, projectKey: string, key: string) {
  queryClient.setQueriesData<LiveBoard>(
    { queryKey: boardKeys.project(projectKey) },
    (board) => board && removeIssue(board, key),
  )
  queryClient.setQueriesData<LiveBacklog>(
    { queryKey: backlogKeys.project(projectKey) },
    (view) => view && removeBacklogIssue(view, key),
  )
}

/** Refetch what an event may have changed that we can't patch by hand. */
function refresh(queryClient: QueryClient, projectKey: string, project = false) {
  void queryClient.invalidateQueries({ queryKey: boardKeys.project(projectKey) })
  void queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) })
  if (project) {
    void queryClient.invalidateQueries({ queryKey: projectKeys.detail(projectKey) })
  }
}

// BLOCK 3: event → patch
export function projectHandlers(
  queryClient: QueryClient,
  projectKey: string,
  onViewers: (userIds: string[]) => void,
): StreamHandlers {
  let ready = false
  const onIssue = (data: unknown) => {
    const event = issueEvent.safeParse(data)
    if (event.success) {
      patchIssue(queryClient, projectKey, fromSnapshot(event.data.snapshot))
    }
  }
  const onComment = (data: unknown) => {
    const event = commentEvent.safeParse(data)
    if (event.success) {
      void queryClient.invalidateQueries({ queryKey: issueKeys.comments(event.data.issueKey) })
    }
  }
  const onSprint = () => {
    refresh(queryClient, projectKey)
    void queryClient.invalidateQueries({ queryKey: projectKeys.list() })
  }
  return {
    // `ready` comes when the stream starts without Last-Event-ID. The first time that's just the start; after
    // that it means we reconnected with nothing to resume from — events may have been missed: refetch.
    ready: () => {
      if (ready) {
        refresh(queryClient, projectKey)
      }
      ready = true
    },
    // the server's replay buffer no longer reaches back to our last event: refetch everything of the project
    resync: () => {
      refresh(queryClient, projectKey, true)
    },
    presence: (data) => {
      const event = presenceEvent.safeParse(data)
      if (event.success) {
        onViewers(event.data.userIds)
      }
    },
    'issue.created': onIssue,
    'issue.updated': onIssue,
    'issue.moved': onIssue,
    'issue.transitioned': onIssue,
    // a collab snapshot was stored (phase 16): an open issue page refetches, which is what turns "Saving…" into "Saved"
    'issue.description-changed': onIssue,
    'issue.deleted': (data) => {
      const event = deletedEvent.safeParse(data)
      if (event.success) {
        dropIssue(queryClient, projectKey, event.data.key)
      }
    },
    // comments: whoever has the issue open sees new ones arrive (the event names the issue by key)
    'comment.added': onComment,
    'comment.edited': onComment,
    'comment.deleted': onComment,
    'sprint.created': onSprint,
    'sprint.updated': onSprint,
    'sprint.started': onSprint,
    'sprint.completed': onSprint,
    'sprint.deleted': onSprint,
    'project.updated': () => {
      void queryClient.invalidateQueries({ queryKey: projectKeys.detail(projectKey) })
    },
    'project.member-changed': () => {
      refresh(queryClient, projectKey, true)
    },
    'workflow.changed': () => {
      refresh(queryClient, projectKey, true)
    },
    'board.changed': () => {
      refresh(queryClient, projectKey, true)
    },
  }
}

// BLOCK 4: the hook ProjectShell calls — one stream per open project, closed when the user leaves it
export function useProjectLive(projectKey: string): ProjectLive {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<StreamStatus>('connecting')
  const [viewers, setViewers] = useState<string[]>([])

  useEffect(() => {
    const handlers = projectHandlers(queryClient, projectKey, setViewers)
    return openStream(`/api/v1/projects/${encodeURIComponent(projectKey)}/stream`, {
      ...handlers,
      onStatus: setStatus,
    })
  }, [queryClient, projectKey])

  return { status, viewers }
}

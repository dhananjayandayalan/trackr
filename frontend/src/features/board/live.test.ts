import { describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/query-client.ts'
import { backlogQuery } from '@/features/backlog/queries.ts'
import { projectQuery } from '@/features/projects/queries.ts'
import type { StreamEventName } from '@/shared/api/sse.ts'
import { cached } from '@/test/cached.ts'
import { marBacklog, marBoard, marketing } from '@/test/msw/handlers.ts'
import { findCard, type LiveBoard } from './board-cache.ts'
import { projectHandlers } from './live.ts'
import { boardQuery } from './queries.ts'
import type { LiveBacklog } from '@/features/backlog/backlog-cache.ts'

// The live dispatcher without a stream: call its handlers the way sse.ts would, and look at the caches.
function setup() {
  const queryClient = createQueryClient()
  queryClient.setQueryData(projectQuery('MAR').queryKey, marketing)
  queryClient.setQueryData<LiveBoard>(boardQuery('MAR').queryKey, marBoard())
  queryClient.setQueryData<LiveBacklog>(backlogQuery('MAR').queryKey, marBacklog())
  const viewers = vi.fn()
  const handlers = projectHandlers(queryClient, 'MAR', viewers)
  const fire = (name: StreamEventName, data: unknown) => {
    handlers[name]?.(data, 'evt')
  }
  const board = () => cached(queryClient, boardQuery('MAR').queryKey)
  const backlog = () => cached(queryClient, backlogQuery('MAR').queryKey)
  return { queryClient, fire, board, backlog, viewers }
}

// an issue event as the api sends it: the event's own fields, the snapshot, the sequence (= the new version)
function moved(key: string, changes: Record<string, unknown>) {
  const snapshot = {
    id: `id-${key}`,
    key,
    type: 'story',
    summary: `Summary of ${key}`,
    statusId: 'st-todo',
    statusCategory: 'todo',
    priority: 'medium',
    assigneeId: null,
    parentId: null,
    sprintId: 's1',
    storyPoints: 3,
    rank: 'A',
    flagged: false,
    deleted: false,
    version: 2,
    labels: [],
    ...changes,
  }
  return { actorId: 'u-grace', statusId: snapshot.statusId, snapshot, sequence: snapshot.version }
}

describe('projectHandlers', () => {
  it("applies someone else's move to the board and the backlog, flashing", () => {
    const { fire, board, backlog } = setup()
    fire('issue.moved', moved('MAR-1', { statusId: 'st-done', statusCategory: 'done', rank: 'B' }))

    expect(board().columns[2]?.cards.map((card) => card.key)).toEqual(['MAR-1'])
    expect(findCard(board(), 'MAR-1')?.card.flash).toBeTypeOf('number')
    const row = backlog().activeSprint?.issues.find((r) => r.key === 'MAR-1')
    expect(row).toMatchObject({ statusId: 'st-done', statusName: 'Done', statusCategory: 'done', version: 2 })
  })

  it('ignores an event no newer than what the cache has (late, or issue.transitioned after issue.moved)', () => {
    const { fire, board } = setup()
    fire('issue.moved', moved('MAR-1', { statusId: 'st-done', rank: 'B' }))
    const after = board()
    fire('issue.transitioned', moved('MAR-1', { statusId: 'st-done', rank: 'B' }))
    fire('issue.updated', moved('MAR-1', { statusId: 'st-prog', version: 1 }))
    expect(board()).toBe(after)
  })

  it('drops events that do not parse', () => {
    const { fire, board } = setup()
    const before = board()
    fire('issue.moved', { sequence: 3, snapshot: { key: 'MAR-1' } })
    fire('issue.moved', 'nonsense')
    expect(board()).toBe(before)
  })

  it('removes a deleted issue everywhere', () => {
    const { fire, board, backlog } = setup()
    fire('issue.deleted', { key: 'MAR-1', issueId: 'id-MAR-1', sequence: 5 })
    expect(findCard(board(), 'MAR-1')).toBeUndefined()
    expect(backlog().activeSprint?.issues.map((r) => r.key)).toEqual(['MAR-3'])
  })

  it('moves a row between backlog lists when its sprint changes', () => {
    const { fire, backlog, board } = setup()
    fire('issue.moved', moved('MAR-5', { sprintId: 's2', rank: 'C' }))
    expect(backlog().backlog.issues.map((r) => r.key)).toEqual(['MAR-6', 'MAR-7'])
    expect(backlog().plannedSprints[0]?.issues.map((r) => r.key)).toEqual(['MAR-5'])
    // s2 isn't the board's sprint: the board doesn't get it
    expect(findCard(board(), 'MAR-5')).toBeUndefined()
  })

  it('refetches on resync, on sprint events, and on a second ready (a reconnect with nothing to resume)', () => {
    const { fire, queryClient } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    fire('ready', {})
    expect(invalidate).not.toHaveBeenCalled()
    fire('ready', {})
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['board', 'MAR'] })
    invalidate.mockClear()
    fire('resync', {})
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['backlog', 'MAR'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['projects', 'detail', 'MAR'] })
    invalidate.mockClear()
    fire('sprint.started', { sequence: 1 })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['board', 'MAR'] })
  })

  it('reports who is here', () => {
    const { fire, viewers } = setup()
    fire('presence', { userIds: ['u-grace'] })
    expect(viewers).toHaveBeenCalledWith(['u-grace'])
  })
})

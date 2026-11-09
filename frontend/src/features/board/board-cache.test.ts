import { describe, expect, it } from 'vitest'
import type { IssueState } from '@/shared/api/issue-state.ts'
import {
  applyIssue,
  applyMove,
  applyRemoteIssue,
  findCard,
  removeIssue,
  restoreCard,
  setVersion,
  type LiveBoard,
  type LiveCard,
} from './board-cache.ts'

// BLOCK 1: a small scrum board — To do (2 cards), In progress (WIP limit 2, 2 cards), Done (empty)
function card(key: string, statusId: string, rank: string, version = 1): LiveCard {
  return {
    key,
    id: `id-${key}`,
    summary: `Summary of ${key}`,
    type: 'story',
    priority: 'medium',
    storyPoints: 3,
    assigneeId: null,
    epicId: null,
    flagged: false,
    rank,
    statusId,
    version,
  }
}

function board(): LiveBoard {
  return {
    board: { id: 'b1', name: 'MAR board', type: 'scrum' },
    sprint: { id: 's1', name: 'MAR Sprint 1', state: 'active' },
    columns: [
      {
        id: 'c-todo',
        name: 'To do',
        statusIds: ['todo'],
        count: 2,
        overLimit: false,
        cards: [card('MAR-1', 'todo', 'H'), card('MAR-2', 'todo', 'P')],
      },
      {
        id: 'c-prog',
        name: 'In progress',
        statusIds: ['prog', 'review'],
        wipLimit: 2,
        count: 2,
        overLimit: false,
        cards: [card('MAR-3', 'prog', 'K'), card('MAR-4', 'review', 'S')],
      },
      { id: 'c-done', name: 'Done', statusIds: ['done'], count: 0, overLimit: false, cards: [] },
    ],
    epics: {},
    people: {},
  }
}

function issue(key: string, changes: Partial<IssueState> = {}): IssueState {
  return {
    id: `id-${key}`,
    key,
    summary: `Summary of ${key}`,
    type: 'story',
    priority: 'medium',
    storyPoints: 3,
    assigneeId: null,
    epicId: null,
    flagged: false,
    rank: 'H',
    statusId: 'todo',
    statusCategory: 'todo',
    sprintId: 's1',
    version: 2,
    deleted: false,
    ...changes,
  }
}

const keys = (b: LiveBoard, column: number) => b.columns[column]?.cards.map((c) => c.key)

// BLOCK 2: optimistic moves
describe('applyMove', () => {
  it('re-ranks within a column: below its old lower neighbour', () => {
    const next = applyMove(board(), { key: 'MAR-1', toStatusId: 'todo', beforeKey: 'MAR-2', afterKey: null })
    expect(keys(next, 0)).toEqual(['MAR-2', 'MAR-1'])
    expect((findCard(next, 'MAR-1')?.card.rank ?? '') > 'P').toBe(true)
    expect(next.columns[0]?.count).toBe(2)
  })

  it('moves between columns: status, position, counts and the WIP flag follow', () => {
    const next = applyMove(board(), {
      key: 'MAR-1',
      toStatusId: 'prog',
      beforeKey: 'MAR-3',
      afterKey: 'MAR-4',
    })
    expect(keys(next, 0)).toEqual(['MAR-2'])
    expect(keys(next, 1)).toEqual(['MAR-3', 'MAR-1', 'MAR-4'])
    const moved = findCard(next, 'MAR-1')?.card
    expect(moved?.statusId).toBe('prog')
    expect(moved && moved.rank > 'K' && moved.rank < 'S').toBe(true)
    expect(next.columns[0]).toMatchObject({ count: 1, overLimit: false })
    expect(next.columns[1]).toMatchObject({ count: 3, overLimit: true })
  })

  it('into an empty column keeps the rank, as the server does without neighbours', () => {
    const next = applyMove(board(), { key: 'MAR-4', toStatusId: 'done', beforeKey: null, afterKey: null })
    expect(keys(next, 2)).toEqual(['MAR-4'])
    expect(findCard(next, 'MAR-4')?.card.rank).toBe('S')
  })

  it('never mutates its input', () => {
    const original = board()
    const snapshot = structuredClone(original)
    applyMove(original, { key: 'MAR-1', toStatusId: 'done', beforeKey: null, afterKey: null })
    expect(original).toEqual(snapshot)
  })

  it('leaves the board alone for an unknown card or a status without a column', () => {
    const original = board()
    expect(applyMove(original, { key: 'NOPE-1', toStatusId: 'done', beforeKey: null, afterKey: null })).toBe(
      original,
    )
    expect(applyMove(original, { key: 'MAR-1', toStatusId: 'gone', beforeKey: null, afterKey: null })).toBe(
      original,
    )
  })
})

// BLOCK 3: what the server says
describe('applyIssue / applyRemoteIssue', () => {
  it('ignores events that are not newer than the card (late, or the twin of issue.moved)', () => {
    const original = board()
    expect(applyRemoteIssue(original, issue('MAR-3', { statusId: 'done', version: 1 }))).toBe(original)
    const once = applyRemoteIssue(original, issue('MAR-3', { statusId: 'done', version: 2 }))
    const twice = applyRemoteIssue(once, issue('MAR-3', { statusId: 'done', version: 2 }))
    expect(twice).toBe(once)
  })

  it('moves a card someone else moved, sorted by the server rank, and makes it flash', () => {
    const next = applyRemoteIssue(board(), issue('MAR-4', { statusId: 'todo', rank: 'J', version: 5 }), 1234)
    expect(keys(next, 0)).toEqual(['MAR-1', 'MAR-4', 'MAR-2'])
    expect(findCard(next, 'MAR-4')?.card).toMatchObject({ flash: 1234, version: 5 })
    expect(next.columns[1]).toMatchObject({ count: 1, overLimit: false })
  })

  it('adds a card created in this sprint, and removes one moved out of it', () => {
    const added = applyIssue(board(), issue('MAR-9', { rank: 'Z', version: 0 }))
    expect(keys(added, 0)).toEqual(['MAR-1', 'MAR-2', 'MAR-9'])
    expect(added.columns[0]?.count).toBe(3)

    const left = applyIssue(board(), issue('MAR-2', { sprintId: null, version: 3 }))
    expect(keys(left, 0)).toEqual(['MAR-1'])
    expect(left.columns[0]?.count).toBe(1)
  })

  it('keeps epics and deleted issues off the board', () => {
    expect(keys(applyIssue(board(), issue('MAR-8', { type: 'epic' })), 0)).toEqual(['MAR-1', 'MAR-2'])
    expect(keys(applyIssue(board(), issue('MAR-1', { deleted: true, version: 9 })), 0)).toEqual(['MAR-2'])
  })

  it("keeps a subtask's epic, which events don't carry", () => {
    const withEpic = board()
    const first = withEpic.columns[0]?.cards[0]
    if (first) first.epicId = 'epic-1'
    const next = applyIssue(
      withEpic,
      issue('MAR-1', { type: 'subtask', epicId: undefined, summary: 'renamed' }),
    )
    expect(findCard(next, 'MAR-1')?.card).toMatchObject({ epicId: 'epic-1', summary: 'renamed' })
  })
})

// BLOCK 4: rolling back
describe('restoreCard / removeIssue / setVersion', () => {
  it('puts one card back and keeps every other change', () => {
    const previous = board()
    const moved = applyMove(previous, { key: 'MAR-1', toStatusId: 'done', beforeKey: null, afterKey: null })
    // meanwhile someone else's change to another card arrived
    const live = applyRemoteIssue(moved, issue('MAR-3', { statusId: 'done', rank: 'A', version: 7 }))
    const restored = restoreCard(live, previous, 'MAR-1')
    expect(keys(restored, 0)).toEqual(['MAR-1', 'MAR-2'])
    expect(keys(restored, 2)).toEqual(['MAR-3'])
  })

  it('does not restore over a newer version from someone else', () => {
    const previous = board()
    const newer = applyRemoteIssue(previous, issue('MAR-1', { statusId: 'done', version: 4 }))
    expect(restoreCard(newer, previous, 'MAR-1')).toBe(newer)
  })

  it('removes a card and recounts', () => {
    const next = removeIssue(board(), 'MAR-3')
    expect(keys(next, 1)).toEqual(['MAR-4'])
    expect(next.columns[1]?.count).toBe(1)
  })

  it('bumps only the version', () => {
    const next = setVersion(board(), 'MAR-2', 6)
    expect(findCard(next, 'MAR-2')?.card).toMatchObject({ version: 6, rank: 'P', statusId: 'todo' })
    expect(setVersion(next, 'MAR-2', 5)).toBe(next)
  })
})

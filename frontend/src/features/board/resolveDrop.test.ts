import { describe, expect, it } from 'vitest'
import { marBoard, marketing } from '@/test/msw/handlers.ts'
import { transitionAllowed } from './board-view.ts'
import type { LiveColumn } from './board-cache.ts'
import { resolveDrop, type CardDragData, type DropTargetData } from './useBoardDnd.ts'

// Where a drop lands, without a browser: the board fixture's columns, and the fixture workflow
// (To do → In progress / Done; In progress → To do / Done; Done → In progress only).
const board = marBoard()
const column = (index: number) => board.columns[index] as LiveColumn
const allowed = (from: string, to: string) => transitionAllowed(marketing.transitions, from, to)

function drag(key: string, statusId: string, columnId: string): CardDragData {
  return { type: 'card', key, statusId, columnId, lane: 'all', version: 1 }
}

function onCard(key: string, columnId: string, edge: 'top' | 'bottom'): DropTargetData {
  return { type: 'card', key, columnId, lane: 'all', edge }
}

describe('resolveDrop', () => {
  it('re-ranks within a column: below the card it was dropped under', () => {
    const move = resolveDrop({
      source: drag('MAR-1', 'st-todo', 'col-todo'),
      target: onCard('MAR-2', 'col-todo', 'bottom'),
      column: column(0),
      cards: column(0).cards,
      allowed,
    })
    expect(move).toEqual({ key: 'MAR-1', toStatusId: 'st-todo', beforeKey: 'MAR-2', afterKey: null })
  })

  it('crosses columns: the column status, between the two cards around the drop line', () => {
    const move = resolveDrop({
      source: drag('MAR-2', 'st-todo', 'col-todo'),
      target: onCard('MAR-3', 'col-prog', 'top'),
      column: column(1),
      cards: column(1).cards,
      allowed,
    })
    expect(move).toEqual({ key: 'MAR-2', toStatusId: 'st-prog', beforeKey: null, afterKey: 'MAR-3' })
  })

  it('a drop on empty column space goes to the bottom', () => {
    const move = resolveDrop({
      source: drag('MAR-1', 'st-todo', 'col-todo'),
      target: { type: 'column', columnId: 'col-prog', lane: 'all' },
      column: column(1),
      cards: column(1).cards,
      allowed,
    })
    expect(move).toEqual({ key: 'MAR-1', toStatusId: 'st-prog', beforeKey: 'MAR-3', afterKey: null })
  })

  it('nothing to do when dropped back where it was', () => {
    // MAR-1 sits above MAR-2: dropping it on MAR-2's top edge changes nothing
    expect(
      resolveDrop({
        source: drag('MAR-1', 'st-todo', 'col-todo'),
        target: onCard('MAR-2', 'col-todo', 'top'),
        column: column(0),
        cards: column(0).cards,
        allowed,
      }),
    ).toBeNull()
  })

  it('nothing to do when the workflow has no way into that column', () => {
    const done = { ...column(2), cards: [] }
    const todo = column(0)
    // from Done, only In progress is allowed: To do is closed
    expect(
      resolveDrop({
        source: drag('MAR-9', 'st-done', done.id),
        target: { type: 'column', columnId: todo.id, lane: 'all' },
        column: todo,
        cards: todo.cards,
        allowed,
      }),
    ).toBeNull()
  })
})

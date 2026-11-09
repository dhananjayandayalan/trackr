import type { IssueState } from '@/shared/api/issue-state.ts'
import type { BoardCard, BoardColumn, BoardView } from '@/shared/api/types.ts'
import { between, compareRanks } from '@/shared/lib/rank.ts'

/**
 * The board cache's transforms (07-frontend §3). Pure functions: board in, new board out, nothing mutated.
 * TanStack Query and React both notice changes by reference (`old !== new`), so a transform that changed an
 * array in place would update the cache without anyone re-rendering. Being pure also means they're tested
 * like any function — no React, no network (board-cache.test.ts).
 */

// BLOCK 1: the cached shape. A card may carry `flash`: when someone else changed it (a timestamp, so a
// second change re-triggers the animation). Optional, so the api's BoardView is already a LiveBoard.
export type LiveCard = BoardCard & { flash?: number }
export type LiveColumn = Omit<BoardColumn, 'cards'> & { cards: LiveCard[] }
export type LiveBoard = Omit<BoardView, 'columns'> & { columns: LiveColumn[] }

export interface Move {
  key: string
  toStatusId: string
  /** the card that should end up directly above it (null: none — top of the column, or no neighbours given) */
  beforeKey: string | null
  /** the card that should end up directly below it */
  afterKey: string | null
}

export function findCard(board: LiveBoard, key: string): { card: LiveCard; column: LiveColumn } | undefined {
  for (const column of board.columns) {
    const card = column.cards.find((c) => c.key === key)
    if (card) {
      return { card, column }
    }
  }
  return undefined
}

export function columnOf(board: LiveBoard, statusId: string): LiveColumn | undefined {
  return board.columns.find((column) => column.statusIds.includes(statusId))
}

// BLOCK 2: applyMove — the optimistic update. Take the card out, give it a provisional rank between its new
// neighbours, put it in at the drop position, fix the counts. The server recomputes the rank from the
// neighbours' current ranks; its answer replaces ours a moment later (applyIssue).
export function applyMove(board: LiveBoard, move: Move): LiveBoard {
  const found = findCard(board, move.key)
  const target = columnOf(board, move.toStatusId)
  if (!found || !target) {
    return board
  }
  const removed = withoutCard(board, move.key)
  const targetCards = removed.columns.find((column) => column.id === target.id)?.cards ?? []
  const before = move.beforeKey === null ? undefined : targetCards.find((c) => c.key === move.beforeKey)
  const after = move.afterKey === null ? undefined : targetCards.find((c) => c.key === move.afterKey)

  // no neighbours: the server keeps the rank (03-api §3.4), so do we
  let rank = found.card.rank
  if (before || after) {
    try {
      rank = between(before?.rank ?? null, after?.rank ?? null)
    } catch {
      // neighbours out of order (a stale view): keep the old rank, the server's answer fixes the position
    }
  }

  const moved: LiveCard = { ...found.card, statusId: move.toStatusId, rank }
  const index = after
    ? targetCards.indexOf(after)
    : before
      ? targetCards.indexOf(before) + 1
      : sortedIndex(targetCards, rank)
  return withCardAt(removed, target.id, moved, index)
}

// BLOCK 3: applyIssue — the server says how an issue is now (a move's answer, or someone's change arriving
// live). An answer no newer than the card we hold is ignored: events arrive late and twice (a status change
// sends issue.moved AND issue.transitioned, with the same sequence), and an older one must never overwrite a
// newer one. Then: take the card out, and put it back where the issue now belongs — if it belongs here.
export function applyIssue(board: LiveBoard, issue: IssueState, options: { flash?: number } = {}): LiveBoard {
  const existing = findCard(board, issue.key)
  if (existing && existing.card.version >= issue.version) {
    return board
  }
  const next = existing ? withoutCard(board, issue.key) : board
  const column = columnOf(next, issue.statusId)
  if (!column || !belongsOnBoard(next, issue)) {
    return next
  }
  const card: LiveCard = {
    key: issue.key,
    id: issue.id,
    summary: issue.summary,
    type: issue.type,
    priority: issue.priority,
    storyPoints: issue.storyPoints,
    assigneeId: issue.assigneeId,
    // a subtask's epic isn't in the event: keep the one we had
    epicId: issue.epicId === undefined ? (existing?.card.epicId ?? null) : issue.epicId,
    flagged: issue.flagged,
    rank: issue.rank,
    statusId: issue.statusId,
    version: issue.version,
    ...(options.flash === undefined ? {} : { flash: options.flash }),
  }
  return withCardAt(next, column.id, card, sortedIndex(column.cards, card.rank))
}

/** What a remote change looks like on screen: applied, and flashing. */
export function applyRemoteIssue(board: LiveBoard, issue: IssueState, now = Date.now()): LiveBoard {
  return applyIssue(board, issue, { flash: now })
}

export function removeIssue(board: LiveBoard, key: string): LiveBoard {
  return findCard(board, key) ? withoutCard(board, key) : board
}

/**
 * Undo one card's optimistic move: back to where `previous` had it, everything else as it is now. Restoring
 * the whole previous board instead would also undo other moves that finished in the meantime.
 */
export function restoreCard(board: LiveBoard, previous: LiveBoard, key: string): LiveBoard {
  const before = findCard(previous, key)
  const now = findCard(board, key)
  if (!before) {
    return removeIssue(board, key)
  }
  // our move failed, but a newer version arrived meanwhile (someone else moved it): that one stays
  if (now && now.card.version > before.card.version) {
    return board
  }
  const removed = withoutCard(board, key)
  const cards = removed.columns.find((column) => column.id === before.column.id)?.cards ?? []
  return withCardAt(removed, before.column.id, before.card, sortedIndex(cards, before.card.rank))
}

/** Only the version (and nothing about position): a later move of the same card is still on its way. */
export function setVersion(board: LiveBoard, key: string, version: number): LiveBoard {
  const found = findCard(board, key)
  if (!found || found.card.version >= version) {
    return board
  }
  return mapColumn(board, found.column.id, (column) => ({
    ...column,
    cards: column.cards.map((card) => (card.key === key ? { ...card, version } : card)),
  }))
}

// BLOCK 4: helpers. Counts follow the cards: one out = count - 1, one in = count + 1, and the WIP flag is
// recomputed from the new count. (The api counts the whole column, filters or not; so do we.)
function belongsOnBoard(board: LiveBoard, issue: IssueState): boolean {
  if (issue.deleted || issue.type === 'epic') {
    return false
  }
  // scrum: the board shows one sprint; kanban: every issue in a status that has a column
  return board.board.type === 'kanban' || board.sprint?.id === issue.sprintId
}

function withoutCard(board: LiveBoard, key: string): LiveBoard {
  const found = findCard(board, key)
  if (!found) {
    return board
  }
  return mapColumn(board, found.column.id, (column) =>
    counted({ ...column, cards: column.cards.filter((card) => card.key !== key) }, column.count - 1),
  )
}

function withCardAt(board: LiveBoard, columnId: string, card: LiveCard, index: number): LiveBoard {
  return mapColumn(board, columnId, (column) => {
    const cards = [...column.cards]
    cards.splice(Math.max(0, Math.min(index, cards.length)), 0, card)
    return counted({ ...column, cards }, column.count + 1)
  })
}

function counted(column: LiveColumn, count: number): LiveColumn {
  const safe = Math.max(count, column.cards.length)
  return { ...column, count: safe, overLimit: column.wipLimit != null && safe > column.wipLimit }
}

function mapColumn(
  board: LiveBoard,
  columnId: string,
  change: (column: LiveColumn) => LiveColumn,
): LiveBoard {
  return {
    ...board,
    columns: board.columns.map((column) => (column.id === columnId ? change(column) : column)),
  }
}

// the first position whose rank sorts after this one: a card goes where the server's ORDER BY rank puts it
function sortedIndex(cards: readonly LiveCard[], rank: string): number {
  const index = cards.findIndex((card) => compareRanks(card.rank, rank) > 0)
  return index === -1 ? cards.length : index
}

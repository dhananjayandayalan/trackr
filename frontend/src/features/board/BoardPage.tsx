import { useSuspenseQuery } from '@tanstack/react-query'
import { Link, getRouteApi } from '@tanstack/react-router'
import { useRef, useState, type ReactNode } from 'react'
import { meQuery } from '@/features/auth/queries.ts'
import { IssuePanel } from '@/features/issue/IssuePanel.tsx'
import { projectQuery } from '@/features/projects/queries.ts'
import { buttonClass } from '@/shared/ui/Button.tsx'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'
import { findCard } from './board-cache.ts'
import { cardFilter, laneOf, lanesOf, transitionAllowed } from './board-view.ts'
import { BoardColumn, BoardColumnHeader } from './BoardColumn.tsx'
import { BoardHeader } from './BoardHeader.tsx'
import { BoardToolbar } from './BoardToolbar.tsx'
import { useMoveIssue } from './mutations.ts'
import { boardQuery } from './queries.ts'
import { resolveDrop, useBoardMonitor, type AcceptsFn, type CardDragData } from './useBoardDnd.ts'
import styles from './BoardPage.module.css'

const route = getRouteApi('/_authed/p/$projectKey/board')

/** /p/$projectKey/board (00-product §3.5): the active sprint's board, or the kanban board. */
export function BoardPage() {
  // BLOCK 1: data — all of it already cached by the route loaders, so this renders at once
  const { projectKey } = route.useParams()
  const search = route.useSearch()
  const navigate = route.useNavigate()
  const { data: project } = useSuspenseQuery(projectQuery(projectKey))
  const { data: me } = useSuspenseQuery(meQuery())
  const query = boardQuery(projectKey, 'active', search.recent ?? false)
  const { data: board } = useSuspenseQuery(query)
  const move = useMoveIssue(projectKey)
  const canEdit = project.myRole !== 'viewer'

  // BLOCK 2: what's on screen: filtered cards, grouped into lanes
  const visible = cardFilter(search, me.id)
  const cardsOnScreen = board.columns.flatMap((column) => column.cards.filter(visible))
  const lanes = lanesOf(board, search.group, cardsOnScreen)
  const cellCards = (columnId: string, lane: string) =>
    board.columns
      .find((column) => column.id === columnId)
      ?.cards.filter((card) => visible(card) && laneOf(card, search.group) === lane) ?? []

  const allowed = (from: string, to: string) => transitionAllowed(project.transitions, from, to)
  const accepts: AcceptsFn = (source, column) =>
    column.id === source.columnId || column.statusIds.some((statusId) => allowed(source.statusId, statusId))

  // BLOCK 3: drag and drop → move. While a card is in the air, columns it can't go to are marked.
  const scroller = useRef<HTMLDivElement>(null)
  const [dragged, setDragged] = useState<CardDragData | null>(null)
  useBoardMonitor(scroller, {
    onDragStart: setDragged,
    onDrop: (source, target) => {
      setDragged(null)
      const column = target && board.columns.find((c) => c.id === target.columnId)
      if (!target || !column || target.lane !== source.lane) {
        return
      }
      const next = resolveDrop({ source, target, column, cards: cellCards(column.id, target.lane), allowed })
      if (next) {
        move.mutate({ ...next, boardKey: query.queryKey, version: source.version })
      }
    },
  })

  /** For the keyboard: the card menu's "Move to…" entries. */
  const moveTo = (key: string, toStatusId: string, position: 'keep' | 'top' | 'bottom') => {
    const found = findCard(board, key)
    const column = board.columns.find((c) => c.statusIds.includes(toStatusId))
    if (!found || !column) {
      return
    }
    const others = column.cards.filter((card) => card.key !== key)
    move.mutate({
      key,
      toStatusId,
      beforeKey: position === 'bottom' ? (others.at(-1)?.key ?? null) : null,
      afterKey: position === 'top' ? (others[0]?.key ?? null) : null,
      boardKey: query.queryKey,
      version: found.card.version,
    })
  }

  // BLOCK 4: a scrum project between sprints has nothing to show
  if (board.board.type === 'scrum' && !board.sprint) {
    return (
      <div className={styles.page}>
        <BoardHeader project={project} board={board} />
        <EmptyState
          icon="board"
          title="No active sprint"
          action={
            <Link
              to="/p/$projectKey/backlog"
              params={{ projectKey }}
              className={buttonClass}
              data-variant="primary"
            >
              Plan a sprint in the backlog
            </Link>
          }
        >
          The board shows the active sprint. Start one from the backlog.
        </EmptyState>
      </div>
    )
  }

  // BLOCK 5: the grid — column headers once, then a row of cells per lane. One scroller: columns scroll
  // sideways together, and the page scrolls down.
  return (
    <div className={styles.page}>
      <BoardHeader project={project} board={board} />
      <BoardToolbar board={board} search={search} />
      <div
        ref={scroller}
        className={styles.scroller}
        style={{ '--columns': board.columns.length }}
        data-dragging={dragged ? true : undefined}
      >
        <div className={styles.grid}>
          {board.columns.map((column) => (
            <BoardColumnHeader key={column.id} column={column} />
          ))}
          {lanes.map((lane) => (
            <Lane key={lane.id} title={lane.title} columns={board.columns.length}>
              {board.columns.map((column) => (
                <BoardColumn
                  key={column.id}
                  projectKey={projectKey}
                  column={column}
                  lane={lane.id}
                  cards={cellCards(column.id, lane.id)}
                  board={board}
                  project={project}
                  canEdit={canEdit}
                  dropDisabled={dragged !== null && !accepts(dragged, column)}
                  accepts={(source) => source.lane === lane.id && accepts(source, column)}
                  allowed={allowed}
                  onMoveTo={moveTo}
                />
              ))}
            </Lane>
          ))}
        </div>
        {cardsOnScreen.length === 0 && board.columns.some((column) => column.cards.length > 0) && (
          <p className={styles.noMatch}>No cards match the filters.</p>
        )}
      </div>
      {/* BLOCK 6: the side panel — ?issue=KEY over the board, which stays mounted under it */}
      {search.issue && (
        <IssuePanel
          issueKey={search.issue}
          onClose={() => {
            void navigate({ search: (prev) => ({ ...prev, issue: undefined }), viewTransition: true })
          }}
        />
      )}
    </div>
  )
}

function Lane({ title, columns, children }: { title: string | null; columns: number; children: ReactNode }) {
  return (
    <>
      {title !== null && (
        <h2 className={styles.laneTitle} style={{ gridColumn: `1 / span ${columns}` }}>
          {title}
        </h2>
      )}
      {children}
    </>
  )
}

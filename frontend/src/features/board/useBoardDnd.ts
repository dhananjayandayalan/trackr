import {
  draggable,
  dropTargetForElements,
  monitorForElements,
} from '@atlaskit/pragmatic-drag-and-drop/adapter/element-adapter'
import { combine } from '@atlaskit/pragmatic-drag-and-drop/utils/combine'
import { preserveOffsetOnSource } from '@atlaskit/pragmatic-drag-and-drop/utils/preserve-offset-on-source'
import { setCustomNativeDragPreview } from '@atlaskit/pragmatic-drag-and-drop/utils/set-custom-native-drag-preview'
import {
  autoScrollForElements,
  autoScrollWindowForElements,
} from '@atlaskit/pragmatic-drag-and-drop-auto-scroll/element'
import { attachClosestEdge } from '@atlaskit/pragmatic-drag-and-drop-hitbox/closest-edge/attach-closest-edge'
import { extractClosestEdge } from '@atlaskit/pragmatic-drag-and-drop-hitbox/closest-edge/extract-closest-edge'
import { useEffect, useEffectEvent, useState, type RefObject } from 'react'
import type { CardEdge } from '@/shared/ui/IssueCard.tsx'
import type { LiveCard, Move } from './board-cache.ts'

/**
 * Pragmatic drag and drop (07-frontend §4): native HTML5 drag and drop underneath — the browser draws the
 * preview, scrolls, and tells us where the pointer is — with small adapters on top: `draggable` (this element
 * can be picked up), `dropTargetForElements` (things can be dropped here), `monitorForElements` (watch every
 * drag on the page, from one place). Each returns a cleanup function, so a useEffect returns them combined.
 */

// BLOCK 0: the data a drag carries. `lane` is the swimlane (group by assignee/epic): a drop may not leave it,
// because changing the assignee or epic isn't what a move does.
export interface CardDragData {
  type: 'card'
  key: string
  statusId: string
  columnId: string
  lane: string
  version: number
}

export type DropTargetData =
  | { type: 'card'; key: string; columnId: string; lane: string; edge: CardEdge | null }
  | { type: 'column'; columnId: string; lane: string }

export function isCardDrag(
  data: Record<string | symbol, unknown>,
): data is Record<string | symbol, unknown> & CardDragData {
  return data.type === 'card' && typeof data.key === 'string'
}

/** Can this card go into this column? Its own column always (re-rank); another one if the workflow allows. */
export type AcceptsFn = (source: CardDragData, column: { id: string; statusIds: string[] }) => boolean

// BLOCK 1 + 2: a card — draggable, and a drop target that knows which half of it the pointer is over
export function useCardDnd(
  ref: RefObject<HTMLElement | null>,
  data: CardDragData,
  accepts: (source: CardDragData) => boolean,
  enabled = true,
): { dragging: boolean; edge: CardEdge | null } {
  const [dragging, setDragging] = useState(false)
  const [edge, setEdge] = useState<CardEdge | null>(null)
  // Effect Events: the drag callbacks always see this render's data, without re-registering the element on
  // every render (the effect below runs once per mounted card)
  const initialData = useEffectEvent(() => ({ ...data }))
  const canAccept = useEffectEvent((source: CardDragData) => accepts(source))
  const targetData = useEffectEvent(() => ({
    type: 'card',
    key: data.key,
    columnId: data.columnId,
    lane: data.lane,
  }))
  const canDrag = useEffectEvent(() => enabled)

  useEffect(() => {
    const element = ref.current
    if (!element) {
      return
    }
    return combine(
      draggable({
        element,
        // viewers can look, not move
        canDrag: () => canDrag(),
        getInitialData: () => initialData(),
        // the preview: a copy of the card, tilted ("drag" in 08-css-volt §7), held where it was grabbed
        onGenerateDragPreview: ({ nativeSetDragImage, location }) => {
          setCustomNativeDragPreview({
            nativeSetDragImage,
            getOffset: preserveOffsetOnSource({ element, input: location.current.input }),
            render: ({ container }) => {
              const preview = document.createElement('div')
              preview.dataset.dragPreview = ''
              preview.style.inlineSize = `${element.getBoundingClientRect().width}px`
              preview.style.padding = '8px'
              preview.append(element.cloneNode(true))
              container.append(preview)
            },
          })
        },
        onDragStart: () => {
          setDragging(true)
        },
        onDrop: () => {
          setDragging(false)
        },
      }),
      dropTargetForElements({
        element,
        canDrop: ({ source }) =>
          isCardDrag(source.data) && source.data.key !== initialData().key && canAccept(source.data),
        // which edge is closer: the drop goes above (top) or below (bottom) this card
        getData: ({ input, element: target }) =>
          attachClosestEdge(targetData(), { input, element: target, allowedEdges: ['top', 'bottom'] }),
        onDrag: ({ self }) => {
          const closest = extractClosestEdge(self.data)
          setEdge(closest === 'top' || closest === 'bottom' ? closest : null)
        },
        onDragLeave: () => {
          setEdge(null)
        },
        onDrop: () => {
          setEdge(null)
        },
      }),
    )
  }, [ref])

  return { dragging, edge }
}

// BLOCK 3: a column (one lane's cell of it) — drops into its empty space land at the bottom
export function useColumnDrop(
  ref: RefObject<HTMLElement | null>,
  target: { columnId: string; lane: string },
  accepts: (source: CardDragData) => boolean,
): boolean {
  const [over, setOver] = useState(false)
  const canAccept = useEffectEvent((source: CardDragData) => accepts(source))
  const targetData = useEffectEvent(() => ({ type: 'column', columnId: target.columnId, lane: target.lane }))

  useEffect(() => {
    const element = ref.current
    if (!element) {
      return
    }
    return dropTargetForElements({
      element,
      canDrop: ({ source }) => isCardDrag(source.data) && canAccept(source.data),
      getData: () => targetData(),
      onDragEnter: () => {
        setOver(true)
      },
      onDragLeave: () => {
        setOver(false)
      },
      onDrop: () => {
        setOver(false)
      },
    })
  }, [ref])

  return over
}

// BLOCK 4 + 5: the monitor (the one place that turns a drop into a move) and auto-scroll — sideways for the
// board's scroller, up and down for the window
export function useBoardMonitor(
  scroller: RefObject<HTMLElement | null>,
  handlers: {
    onDragStart: (source: CardDragData) => void
    onDrop: (source: CardDragData, target: DropTargetData | null) => void
  },
) {
  const start = useEffectEvent((source: CardDragData) => {
    handlers.onDragStart(source)
  })
  const drop = useEffectEvent((source: CardDragData, target: DropTargetData | null) => {
    handlers.onDrop(source, target)
  })

  useEffect(() => {
    const element = scroller.current
    if (!element) {
      return
    }
    // BLOCK 6: combine — one cleanup for everything
    return combine(
      monitorForElements({
        canMonitor: ({ source }) => isCardDrag(source.data),
        onDragStart: ({ source }) => {
          if (isCardDrag(source.data)) start(source.data)
        },
        onDrop: ({ source, location }) => {
          if (!isCardDrag(source.data)) {
            return
          }
          // dropTargets: innermost first — the card under the pointer, then its column
          const innermost = location.current.dropTargets[0]
          drop(source.data, innermost ? toTarget(innermost.data) : null)
        },
      }),
      autoScrollForElements({ element }),
      autoScrollWindowForElements(),
    )
  }, [scroller])
}

function toTarget(data: Record<string | symbol, unknown>): DropTargetData | null {
  if (data.type === 'card' && typeof data.key === 'string') {
    const edge = extractClosestEdge(data)
    return {
      type: 'card',
      key: data.key,
      columnId: String(data.columnId),
      lane: String(data.lane),
      edge: edge === 'top' || edge === 'bottom' ? edge : null,
    }
  }
  if (data.type === 'column') {
    return { type: 'column', columnId: String(data.columnId), lane: String(data.lane) }
  }
  return null
}

/**
 * Where a drop lands, as a move request — pure, so it's unit-tested without a browser. `cards` are the cards
 * the user sees in the target cell, in order (filters applied): the neighbours must be ones they saw.
 * null = nothing to do (dropped where it was, or no allowed status in that column).
 */
export function resolveDrop(args: {
  source: CardDragData
  target: DropTargetData
  column: { id: string; statusIds: string[] }
  cards: readonly LiveCard[]
  allowed: (fromStatusId: string, toStatusId: string) => boolean
}): Move | null {
  const { source, target, column, cards, allowed } = args
  const others = cards.filter((card) => card.key !== source.key)
  let before: LiveCard | undefined
  let after: LiveCard | undefined
  if (target.type === 'card') {
    const index = others.findIndex((card) => card.key === target.key)
    if (index < 0) {
      return null
    }
    if (target.edge === 'top') {
      before = others[index - 1]
      after = others[index]
    } else {
      before = others[index]
      after = others[index + 1]
    }
  } else {
    before = others.at(-1)
  }

  // the column's status: the card's own if the column has it (a re-rank), else the first one allowed
  const toStatusId = column.statusIds.includes(source.statusId)
    ? source.statusId
    : column.statusIds.find((statusId) => allowed(source.statusId, statusId))
  if (toStatusId === undefined) {
    return null
  }

  // dropped back between the neighbours it already had: no request
  if (source.columnId === column.id) {
    const index = cards.findIndex((card) => card.key === source.key)
    if (index >= 0 && cards[index - 1]?.key === before?.key && cards[index + 1]?.key === after?.key) {
      return null
    }
  }
  return { key: source.key, toStatusId, beforeKey: before?.key ?? null, afterKey: after?.key ?? null }
}

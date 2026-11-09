import {
  draggable,
  dropTargetForElements,
  monitorForElements,
} from '@atlaskit/pragmatic-drag-and-drop/adapter/element-adapter'
import { combine } from '@atlaskit/pragmatic-drag-and-drop/utils/combine'
import {
  autoScrollForElements,
  autoScrollWindowForElements,
} from '@atlaskit/pragmatic-drag-and-drop-auto-scroll/element'
import { attachClosestEdge } from '@atlaskit/pragmatic-drag-and-drop-hitbox/closest-edge/attach-closest-edge'
import { extractClosestEdge } from '@atlaskit/pragmatic-drag-and-drop-hitbox/closest-edge/extract-closest-edge'
import { useEffect, useEffectEvent, useState, type RefObject } from 'react'
import type { CardEdge } from '@/shared/ui/IssueCard.tsx'
import type { BacklogMove, ListId, LiveRow } from './backlog-cache.ts'

/**
 * The board's drag and drop pattern (features/board/useBoardDnd.ts) for backlog rows: a row is draggable and
 * a drop target with a closest edge; a list takes drops into its empty space; one monitor turns a drop into
 * a move. No workflow here: any row may go to any open sprint or the backlog.
 */

// drag data can't hold null in a useful way for comparisons: the backlog list travels as this string
const BACKLOG = 'backlog'
const encode = (listId: ListId) => listId ?? BACKLOG
const decode = (value: string): ListId => (value === BACKLOG ? null : value)

export interface RowDragData {
  type: 'row'
  key: string
  listId: ListId
  version: number
}

export type RowDropTarget =
  { type: 'row'; key: string; listId: ListId; edge: CardEdge | null } | { type: 'list'; listId: ListId }

function isRowDrag(data: Record<string | symbol, unknown>): boolean {
  return data.type === 'row' && typeof data.key === 'string'
}

function toDrag(data: Record<string | symbol, unknown>): RowDragData {
  return {
    type: 'row',
    key: String(data.key),
    listId: decode(String(data.listId)),
    version: Number(data.version),
  }
}

// BLOCK 1: a row
export function useRowDnd(
  ref: RefObject<HTMLElement | null>,
  row: { key: string; listId: ListId; version: number },
  enabled: boolean,
): { dragging: boolean; edge: CardEdge | null } {
  const [dragging, setDragging] = useState(false)
  const [edge, setEdge] = useState<CardEdge | null>(null)
  const dragData = useEffectEvent(() => ({
    type: 'row',
    key: row.key,
    listId: encode(row.listId),
    version: row.version,
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
        canDrag: () => canDrag(),
        getInitialData: () => dragData(),
        onDragStart: () => {
          setDragging(true)
        },
        onDrop: () => {
          setDragging(false)
        },
      }),
      dropTargetForElements({
        element,
        canDrop: ({ source }) => isRowDrag(source.data) && source.data.key !== dragData().key,
        getData: ({ input, element: target }) => {
          const { key, listId } = dragData()
          return attachClosestEdge(
            { type: 'row', key, listId },
            { input, element: target, allowedEdges: ['top', 'bottom'] },
          )
        },
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

// BLOCK 2: a list (its body) — drops on empty space land at its end
export function useListDrop(ref: RefObject<HTMLElement | null>, listId: ListId): boolean {
  const [over, setOver] = useState(false)
  const data = useEffectEvent(() => ({ type: 'list', listId: encode(listId) }))
  useEffect(() => {
    const element = ref.current
    if (!element) {
      return
    }
    return dropTargetForElements({
      element,
      canDrop: ({ source }) => isRowDrag(source.data),
      getData: () => data(),
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

/** A long list scrolls inside its section (virtualised): dragging near its edge scrolls it. */
export function useAutoScroll(ref: RefObject<HTMLElement | null>, active: boolean) {
  useEffect(() => {
    const element = ref.current
    if (!element || !active) {
      return
    }
    return autoScrollForElements({ element })
  }, [ref, active])
}

// BLOCK 3: the monitor
export function useBacklogMonitor(onDrop: (source: RowDragData, target: RowDropTarget | null) => void) {
  const drop = useEffectEvent(onDrop)
  useEffect(
    () =>
      combine(
        monitorForElements({
          canMonitor: ({ source }) => isRowDrag(source.data),
          onDrop: ({ source, location }) => {
            const innermost = location.current.dropTargets[0]
            drop(toDrag(source.data), innermost ? toTarget(innermost.data) : null)
          },
        }),
        autoScrollWindowForElements(),
      ),
    [],
  )
}

function toTarget(data: Record<string | symbol, unknown>): RowDropTarget | null {
  if (data.type === 'row' && typeof data.key === 'string') {
    const edge = extractClosestEdge(data)
    return {
      type: 'row',
      key: data.key,
      listId: decode(String(data.listId)),
      edge: edge === 'top' || edge === 'bottom' ? edge : null,
    }
  }
  if (data.type === 'list') {
    return { type: 'list', listId: decode(String(data.listId)) }
  }
  return null
}

/** Where a drop lands, as a move (pure). `rows`: the rows shown in the target list, in order. null = no-op. */
export function resolveBacklogDrop(
  source: RowDragData,
  target: RowDropTarget,
  rows: readonly LiveRow[],
): BacklogMove | null {
  const others = rows.filter((row) => row.key !== source.key)
  let before: LiveRow | undefined
  let after: LiveRow | undefined
  if (target.type === 'row') {
    const index = others.findIndex((row) => row.key === target.key)
    if (index < 0) {
      return null
    }
    ;[before, after] =
      target.edge === 'top' ? [others[index - 1], others[index]] : [others[index], others[index + 1]]
  } else {
    before = others.at(-1)
  }
  if (source.listId === target.listId) {
    const index = rows.findIndex((row) => row.key === source.key)
    if (index >= 0 && rows[index - 1]?.key === before?.key && rows[index + 1]?.key === after?.key) {
      return null
    }
  }
  return {
    key: source.key,
    toSprintId: target.listId,
    beforeKey: before?.key ?? null,
    afterKey: after?.key ?? null,
  }
}

import { QueryClientProvider, QueryObserver } from '@tanstack/react-query'
import { act, renderHook, screen, waitFor } from '@testing-library/react'
import { delay } from 'msw'
import { http, HttpResponse } from 'msw/http'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { createQueryClient } from '@/app/query-client.ts'
import type { MoveRequest } from '@/shared/api/types.ts'
import { Toaster } from '@/shared/ui/Toast.tsx'
import { cached } from '@/test/cached.ts'
import { marBoard, moveAnswer, problem } from '@/test/msw/handlers.ts'
import { server } from '@/test/msw/server.ts'
import { findCard, type LiveBoard } from './board-cache.ts'
import { useMoveIssue } from './mutations.ts'
import { boardQuery } from './queries.ts'

// BLOCK 1: the hook with a real query client whose cache already holds the board (as the route loader leaves it)
function setup() {
  const queryClient = createQueryClient()
  const boardKey = boardQuery('MAR').queryKey
  queryClient.setQueryData<LiveBoard>(boardKey, marBoard())
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      {children}
      <Toaster />
    </QueryClientProvider>
  )
  const { result } = renderHook(() => useMoveIssue('MAR'), { wrapper })
  const board = () => cached(queryClient, boardKey)
  const keys = (column: number) => board().columns[column]?.cards.map((card) => card.key)
  // MAR-1 (To do, version 1) to In progress, above MAR-3
  const moveToProgress = {
    key: 'MAR-1',
    toStatusId: 'st-prog',
    beforeKey: null,
    afterKey: 'MAR-3',
    boardKey,
    version: 1,
  }
  return { queryClient, result, board, keys, moveToProgress }
}

describe('useMoveIssue', () => {
  it('moves the card at once, then takes the rank and version from the server', async () => {
    const sent: MoveRequest[] = []
    server.use(
      http.post('/api/v1/issues/:key/move', async ({ params, request }) => {
        const body = (await request.json()) as MoveRequest
        sent.push(body)
        await delay(50)
        return HttpResponse.json(moveAnswer(String(params.key), body, 'J'))
      }),
    )
    const { result, board, keys, moveToProgress } = setup()

    act(() => {
      result.current.mutate(moveToProgress)
    })
    // optimistic: in the new column before the server has answered, with a provisional rank above MAR-3's "K"
    await waitFor(() => {
      expect(keys(1)).toEqual(['MAR-1', 'MAR-3'])
    })
    expect(findCard(board(), 'MAR-1')?.card.version).toBe(1)
    expect(board().columns[1]).toMatchObject({ count: 2, overLimit: true })

    // the server's answer: its rank, its version
    await waitFor(() => {
      expect(findCard(board(), 'MAR-1')?.card).toMatchObject({ rank: 'J', version: 2, statusId: 'st-prog' })
    })
    expect(sent).toEqual([{ toStatusId: 'st-prog', beforeKey: null, afterKey: 'MAR-3', expectedVersion: 1 }])
  })

  it('409: puts the card back and says why', async () => {
    server.use(
      http.post('/api/v1/issues/:key/move', () =>
        problem(
          409,
          'TRANSITION_NOT_ALLOWED',
          'The workflow does not allow moving from To do to In progress.',
        ),
      ),
    )
    const { result, keys, moveToProgress } = setup()

    act(() => {
      result.current.mutate(moveToProgress)
    })
    expect(await screen.findByText("That move isn't allowed by the workflow.")).toBeInTheDocument()
    expect(keys(0)).toEqual(['MAR-1', 'MAR-2'])
    expect(keys(1)).toEqual(['MAR-3'])
  })

  it('412: puts the card back and refetches the board', async () => {
    let boardRequests = 0
    server.use(
      http.post('/api/v1/issues/:key/move', () =>
        problem(412, 'VERSION_MISMATCH', 'Changed by someone else.'),
      ),
      http.get('/api/v1/projects/MAR/board', () => {
        boardRequests++
        return HttpResponse.json(marBoard())
      }),
    )
    const { result, queryClient, keys, moveToProgress } = setup()
    // invalidation refetches only queries someone is watching: watch the board, as the board page would
    const unsubscribe = new QueryObserver(queryClient, boardQuery('MAR')).subscribe(() => undefined)

    act(() => {
      result.current.mutate(moveToProgress)
    })
    expect(await screen.findByText(/Someone changed this issue a moment ago/)).toBeInTheDocument()
    expect(keys(0)).toEqual(['MAR-1', 'MAR-2'])
    await waitFor(() => {
      expect(boardRequests).toBe(1)
    })
    unsubscribe()
  })

  it('runs quick moves of one card one after the other, each with the version the previous one returned', async () => {
    const versions: number[] = []
    server.use(
      http.post('/api/v1/issues/:key/move', async ({ params, request }) => {
        const body = (await request.json()) as MoveRequest
        versions.push(body.expectedVersion)
        await delay(30)
        return HttpResponse.json(
          moveAnswer(String(params.key), body, body.toStatusId === 'st-done' ? 'B' : 'J'),
        )
      }),
    )
    const { result, board, moveToProgress } = setup()

    act(() => {
      result.current.mutate(moveToProgress)
      result.current.mutate({ ...moveToProgress, toStatusId: 'st-done', afterKey: null })
    })
    // both show at once (the second drop wins on screen) …
    await waitFor(() => {
      expect(findCard(board(), 'MAR-1')?.card.statusId).toBe('st-done')
    })
    // … but the requests went one at a time, the second with version 2 — not the stale 1
    await waitFor(() => {
      expect(versions).toEqual([1, 2])
    })
    await waitFor(() => {
      expect(findCard(board(), 'MAR-1')?.card).toMatchObject({ statusId: 'st-done', version: 3, rank: 'B' })
    })
  })
})

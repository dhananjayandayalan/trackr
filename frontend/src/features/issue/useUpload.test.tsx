import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw/http'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { createQueryClient } from '@/app/query-client.ts'
import type { Attachment, IssueDetails } from '@/shared/api/types.ts'
import { ada, issueDetails } from '@/test/msw/handlers.ts'
import { server } from '@/test/msw/server.ts'
import { issueKeys } from './queries.ts'
import { useUpload } from './useUpload.ts'

const STORAGE = 'http://localhost:8333/trakr-attachments/ws/w1/issues/id-MAR-1/u1/shot.png'

const attachment: Attachment = {
  id: 'att-1',
  fileName: 'shot.png',
  contentType: 'image/png',
  sizeBytes: 5,
  uploader: {
    id: ada.id,
    displayName: ada.displayName,
    initials: ada.initials,
    avatarColor: ada.avatarColor,
  },
  createdAt: '2026-11-09T10:00:00Z',
}

// "S3" is another origin, and jsdom's XMLHttpRequest checks CORS like a browser does: the fake storage answers
// the preflight and the PUT with the headers the bucket's CORS rule makes SeaweedFS send (BucketInitializer)
const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'content-type',
  'Access-Control-Allow-Methods': 'PUT',
}
const preflight = http.options(STORAGE, () => new HttpResponse(null, { status: 200, headers: cors }))

// The three steps against MSW: the api's two endpoints, and "S3" — the presigned URL MSW also answers
// (XMLHttpRequest in jsdom is intercepted like fetch).
function setup() {
  const queryClient = createQueryClient()
  queryClient.setQueryData(issueKeys.detail('MAR-1'), issueDetails('MAR-1'))
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, ...renderHook(() => useUpload('MAR-1'), { wrapper }) }
}

describe('useUpload', () => {
  it('presigns, PUTs to storage with the signed headers, confirms, and adds the file to the issue', async () => {
    const calls: string[] = []
    server.use(
      preflight,
      http.post('/api/v1/issues/:key/attachments/uploads', async ({ request }) => {
        calls.push(`presign ${JSON.stringify(await request.json())}`)
        return HttpResponse.json({
          uploadId: 'up-1',
          objectKey: 'ws/…/shot.png',
          url: STORAGE,
          headers: { 'Content-Type': 'image/png' },
        })
      }),
      http.put(STORAGE, async ({ request }) => {
        calls.push(`put ${request.headers.get('content-type') ?? ''} ${await request.text()}`)
        return new HttpResponse(null, { status: 200, headers: cors })
      }),
      http.post('/api/v1/issues/:key/attachments', async ({ request }) => {
        calls.push(`confirm ${JSON.stringify(await request.json())}`)
        return HttpResponse.json(attachment, { status: 201 })
      }),
    )
    const { result, queryClient } = setup()

    await act(() => result.current.upload([new File(['hello'], 'shot.png', { type: 'image/png' })]))

    expect(calls).toEqual([
      'presign {"fileName":"shot.png","contentType":"image/png","sizeBytes":5}',
      'put image/png hello',
      'confirm {"uploadId":"up-1"}',
    ])
    expect(queryClient.getQueryData<IssueDetails>(issueKeys.detail('MAR-1'))?.attachments).toEqual([
      attachment,
    ])
    // done: its progress row is gone
    expect(result.current.items).toEqual([])
  })

  it('keeps a failed upload in the list with the reason, and never confirms it', async () => {
    let confirmed = false
    server.use(
      preflight,
      http.post('/api/v1/issues/:key/attachments/uploads', () =>
        HttpResponse.json({
          uploadId: 'up-2',
          objectKey: 'k',
          url: STORAGE,
          headers: { 'Content-Type': 'image/png' },
        }),
      ),
      http.put(STORAGE, () => new HttpResponse('SignatureDoesNotMatch', { status: 403, headers: cors })),
      http.post('/api/v1/issues/:key/attachments', () => {
        confirmed = true
        return HttpResponse.json(attachment, { status: 201 })
      }),
    )
    const { result } = setup()

    await act(() => result.current.upload([new File(['x'], 'shot.png', { type: 'image/png' })]))

    await waitFor(() => {
      expect(result.current.items).toEqual([
        expect.objectContaining({
          fileName: 'shot.png',
          status: 'error',
          error: 'The storage refused the file (HTTP 403).',
        }),
      ])
    })
    expect(confirmed).toBe(false)
  })

  it('refuses a file over 25 MB without asking the api', async () => {
    let asked = false
    server.use(
      http.post('/api/v1/issues/:key/attachments/uploads', () => {
        asked = true
        return HttpResponse.json({})
      }),
    )
    const { result } = setup()
    const huge = new File(['x'], 'huge.zip', { type: 'application/zip' })
    Object.defineProperty(huge, 'size', { value: 26 * 1024 * 1024 })

    await act(() => result.current.upload([huge]))

    expect(result.current.items).toEqual([
      expect.objectContaining({ status: 'error', error: 'Files can be at most 25 MB.' }),
    ])
    expect(asked).toBe(false)
  })
})

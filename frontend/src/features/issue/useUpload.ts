import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, unwrap } from '@/shared/api/client.ts'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import type { Attachment, UploadTicket } from '@/shared/api/types.ts'
import { addAttachment } from './mutations.ts'

/** 09-security §4; the api checks too — this only spares a round trip for a file that can't be accepted */
export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024

/**
 * One file to an issue, in the three steps of 03-api §3.6:
 * 1. ask the api for a presigned PUT (it checks access, size and type, and remembers the pending upload);
 * 2. PUT the file straight to S3 — the api never sees the bytes;
 * 3. confirm, so the api checks the object in S3 and records it.
 */
export async function uploadFile(
  issueKey: string,
  file: File,
  onProgress: (fraction: number) => void = () => undefined,
): Promise<Attachment> {
  const ticket = await unwrap(
    api.POST('/api/v1/issues/{key}/attachments/uploads', {
      params: { path: { key: issueKey } },
      body: {
        fileName: file.name,
        contentType: file.type || 'application/octet-stream',
        sizeBytes: file.size,
      },
    }),
  )
  await putToStorage(ticket, file, onProgress)
  return unwrap(
    api.POST('/api/v1/issues/{key}/attachments', {
      params: { path: { key: issueKey } },
      body: { uploadId: ticket.uploadId },
    }),
  )
}

/**
 * Step 2 with XMLHttpRequest, not fetch: fetch has no upload progress. Its response can be read as a stream,
 * but the request side has no events — the only workaround is a ReadableStream body (`duplex: 'half'`) that
 * counts bytes as the browser pulls them, which Chrome supports only over HTTP/2+, Firefox and Safari not at
 * all, and which measures bytes handed to the network stack rather than sent. XHR's `upload.onprogress` has
 * worked everywhere for fifteen years.
 */
function putToStorage(ticket: UploadTicket, file: File, onProgress: (fraction: number) => void) {
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', ticket.url)
    // exactly the headers that were signed; Content-Length the browser sets from the body itself
    for (const [name, value] of Object.entries(ticket.headers)) {
      xhr.setRequestHeader(name, value)
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress(event.loaded / event.total)
      }
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress(1)
        resolve()
      } else {
        reject(new Error(`The storage refused the file (HTTP ${String(xhr.status)}).`))
      }
    }
    // CORS failures land here too: the browser hides the reason from scripts on purpose
    xhr.onerror = () => {
      reject(new Error('The upload was interrupted. Check your connection and try again.'))
    }
    xhr.send(file)
  })
}

export interface UploadItem {
  id: number
  fileName: string
  /** 0…1 */
  progress: number
  status: 'uploading' | 'done' | 'error'
  error?: string
}

let nextId = 1

/**
 * Several files at once, each with its own progress bar. A finished file joins the issue's attachment list in
 * the cache (the confirm answer is the attachment, so no refetch) and its progress row goes away.
 */
export function useUpload(issueKey: string) {
  const queryClient = useQueryClient()
  const [items, setItems] = useState<UploadItem[]>([])

  const update = (id: number, change: Partial<UploadItem>) => {
    setItems((current) => current.map((item) => (item.id === id ? { ...item, ...change } : item)))
  }

  const upload = (files: Iterable<File>) =>
    Promise.all(
      [...files].map(async (file) => {
        const id = nextId++
        if (file.size > MAX_UPLOAD_BYTES) {
          setItems((current) => [
            ...current,
            { id, fileName: file.name, progress: 0, status: 'error', error: 'Files can be at most 25 MB.' },
          ])
          return
        }
        setItems((current) => [...current, { id, fileName: file.name, progress: 0, status: 'uploading' }])
        try {
          const attachment = await uploadFile(issueKey, file, (progress) => {
            update(id, { progress })
          })
          addAttachment(queryClient, issueKey, attachment)
          setItems((current) => current.filter((item) => item.id !== id))
        } catch (error) {
          // an api problem says what was wrong; our own storage errors (above) have their message too
          const text = isApiError(error) || !(error instanceof Error) ? messageOf(error) : error.message
          update(id, { status: 'error', error: text })
        }
      }),
    )

  const dismiss = (id: number) => {
    setItems((current) => current.filter((item) => item.id !== id))
  }

  return { items, upload, dismiss }
}

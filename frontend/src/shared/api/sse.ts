/**
 * Server-Sent Events (03-api §4): one long GET whose response never ends; the server writes an event whenever
 * something happens. The browser's EventSource does the hard parts: it parses the stream, reconnects after a
 * dropped connection, and on reconnect sends the id of the last event it received as `Last-Event-ID`, so the
 * server can replay what was missed. We never write that header — the browser does, on every automatic retry.
 */

// BLOCK 1: types. Every event name the project stream sends (the api forwards each Kafka event type, minus
// "trakr." and the version suffix). Named events only fire their own listeners, never `onmessage`.
export const streamEventNames = [
  'ready',
  'resync',
  'presence',
  'issue.created',
  'issue.updated',
  'issue.transitioned',
  'issue.moved',
  'issue.deleted',
  'issue.linked',
  'issue.work-logged',
  'issue.description-changed',
  'comment.added',
  'comment.edited',
  'comment.deleted',
  'sprint.created',
  'sprint.updated',
  'sprint.started',
  'sprint.completed',
  'sprint.deleted',
  'project.updated',
  'project.archived',
  'project.member-changed',
  'workflow.changed',
  'board.changed',
] as const

export type StreamEventName = (typeof streamEventNames)[number]
/** connecting: first attempt; open: events flow; reconnecting: lost, retrying; closed: we closed it. */
export type StreamStatus = 'connecting' | 'open' | 'reconnecting' | 'closed'

export type StreamHandlers = Partial<Record<StreamEventName, (data: unknown, id: string) => void>> & {
  onStatus?: (status: StreamStatus) => void
}

export interface StreamOptions {
  /** for tests: a fake EventSource class */
  EventSource?: typeof EventSource
  /** first delay before reopening a stream the browser gave up on; doubles up to 30 s */
  retryMs?: number
}

const MAX_RETRY_MS = 30_000

// BLOCK 2: open. `withCredentials` matters only cross-origin; same-origin requests send the session cookie anyway.
export function openStream(url: string, handlers: StreamHandlers, options: StreamOptions = {}): () => void {
  const Source = options.EventSource ?? ('EventSource' in globalThis ? globalThis.EventSource : undefined)
  // no EventSource at all (jsdom in tests): stay offline - the screens still work, just not live
  if (Source === undefined) {
    handlers.onStatus?.('closed')
    return () => undefined
  }
  const firstRetry = options.retryMs ?? 1000
  let source: EventSource | undefined
  let retry = firstRetry
  let timer: ReturnType<typeof setTimeout> | undefined
  let closed = false

  const status = (value: StreamStatus) => {
    handlers.onStatus?.(value)
  }

  const connect = () => {
    const es = new Source(url, { withCredentials: true })
    source = es
    for (const name of streamEventNames) {
      const handler = handlers[name]
      if (handler) {
        es.addEventListener(name, (event) => {
          const message = event as MessageEvent<string>
          let data: unknown
          try {
            data = JSON.parse(message.data)
          } catch {
            return // a garbled frame is skipped, the stream goes on
          }
          handler(data, message.lastEventId)
        })
      }
    }

    // BLOCK 3: status. onopen: connected (again). onerror has two meanings, told apart by readyState:
    // CONNECTING — the browser is already retrying on its own (and will send Last-Event-ID);
    // CLOSED — it gave up (the server answered 401/502, or not with text/event-stream). Then we retry
    // ourselves with backoff; a new EventSource can't send Last-Event-ID, so the server starts with `ready`,
    // and the caller must treat that as "you may have missed something".
    es.onopen = () => {
      retry = firstRetry
      status('open')
    }
    es.onerror = () => {
      if (closed) {
        return
      }
      if (es.readyState === Source.CLOSED) {
        es.close()
        status('reconnecting')
        timer = setTimeout(connect, retry)
        retry = Math.min(retry * 2, MAX_RETRY_MS)
      } else {
        status('reconnecting')
      }
    }
  }

  status('connecting')
  connect()

  // BLOCK 4: close + cleanup — the function a useEffect returns
  return () => {
    closed = true
    clearTimeout(timer)
    source?.close()
    status('closed')
  }
}

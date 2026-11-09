import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { openStream, type StreamStatus } from './sse.ts'

// BLOCK 1: a fake EventSource — just enough of the real one: listeners, readyState, the two callbacks,
// and test-only methods to play the server's part.
class FakeEventSource extends EventTarget {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  static instances: FakeEventSource[] = []

  readyState = FakeEventSource.CONNECTING
  onopen: ((event: Event) => void) | null = null
  onerror: ((event: Event) => void) | null = null

  constructor(
    readonly url: string,
    readonly init?: EventSourceInit,
  ) {
    super()
    FakeEventSource.instances.push(this)
  }

  close() {
    this.readyState = FakeEventSource.CLOSED
  }

  open() {
    this.readyState = FakeEventSource.OPEN
    this.onopen?.(new Event('open'))
  }

  emit(name: string, data: string, id = '') {
    this.dispatchEvent(new MessageEvent(name, { data, lastEventId: id }))
  }

  fail(gaveUp: boolean) {
    this.readyState = gaveUp ? FakeEventSource.CLOSED : FakeEventSource.CONNECTING
    this.onerror?.(new Event('error'))
  }
}

const Fake = FakeEventSource as unknown as typeof EventSource
function latest(): FakeEventSource {
  const source = FakeEventSource.instances.at(-1)
  if (!source) throw new Error('no EventSource was opened')
  return source
}

beforeEach(() => {
  FakeEventSource.instances = []
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('openStream', () => {
  it('passes each named event to its handler, parsed, with the event id', () => {
    const moved = vi.fn()
    openStream('/api/v1/projects/MAR/stream', { 'issue.moved': moved }, { EventSource: Fake })
    expect(latest().url).toBe('/api/v1/projects/MAR/stream')
    expect(latest().init).toEqual({ withCredentials: true })

    latest().emit('issue.moved', '{"key":"MAR-1","sequence":4}', 'evt-1')
    latest().emit('issue.created', '{"key":"MAR-2"}') // no handler: ignored
    latest().emit('issue.moved', 'not json') // garbled: skipped
    expect(moved).toHaveBeenCalledTimes(1)
    expect(moved).toHaveBeenCalledWith({ key: 'MAR-1', sequence: 4 }, 'evt-1')
  })

  it('reports open, then reconnecting while the browser retries by itself', () => {
    const statuses: StreamStatus[] = []
    openStream('/s', { onStatus: (s) => statuses.push(s) }, { EventSource: Fake })
    latest().open()
    latest().fail(false)
    latest().open()
    expect(statuses).toEqual(['connecting', 'open', 'reconnecting', 'open'])
    expect(FakeEventSource.instances).toHaveLength(1) // the browser's own retry: same EventSource
  })

  it('opens a new EventSource, with backoff, when the browser gave up', () => {
    openStream('/s', {}, { EventSource: Fake, retryMs: 1000 })
    latest().fail(true)
    expect(FakeEventSource.instances).toHaveLength(1)
    vi.advanceTimersByTime(1000)
    expect(FakeEventSource.instances).toHaveLength(2)

    latest().fail(true)
    vi.advanceTimersByTime(1000)
    expect(FakeEventSource.instances).toHaveLength(2) // second wait is 2 s
    vi.advanceTimersByTime(1000)
    expect(FakeEventSource.instances).toHaveLength(3)
  })

  it('closes the connection and stops retrying on cleanup', () => {
    const statuses: StreamStatus[] = []
    const close = openStream('/s', { onStatus: (s) => statuses.push(s) }, { EventSource: Fake })
    const first = latest()
    first.fail(true)
    close()
    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(first.readyState).toBe(FakeEventSource.CLOSED)
    expect(statuses.at(-1)).toBe('closed')
  })
})

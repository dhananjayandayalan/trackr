import * as decoding from 'lib0/decoding'
import * as encoding from 'lib0/encoding'
import { WebsocketProvider } from 'y-websocket'
import * as Y from 'yjs'

/**
 * One issue's shared description (06-collab §2–§4): a Yjs document, kept in step with everyone else's through the
 * collab app's WebSocket. y-websocket's WebsocketProvider speaks the standard protocol (sync, awareness); we add
 * our own messages to its handler table, which is indexed by the frame's first varUint:
 *
 * - 101 trakrCheckpoint (server → us): "send me a snapshot up to update N"; we answer 102 at once.
 * - 103 trakrSeed (server → us): "this document is new, fill it from the issue's stored description".
 */

// BLOCK 1: our message types (the Java side: collab/protocol/Messages.java)
export const messageCheckpoint = 101
export const messageSnapshot = 102
export const messageSeed = 103

/** The Yjs field Tiptap's Collaboration extension binds to. */
export const descriptionField = 'description'

/** What a snapshot needs from the editor: its text and its HTML (the api sanitises the HTML). */
export interface SnapshotSource {
  getText: () => string
  getHTML: () => string
}

export interface CollabUser {
  name: string
  /**
   * The person's avatar colour by name ('pink', 'cyan', …), not a CSS value: everyone else turns it into
   * var(--avatar-…) through an allow-list. A colour string from another browser must never reach a style attribute.
   */
  avatar: string
}

export interface TrakrCollab {
  doc: Y.Doc
  provider: WebsocketProvider
  /** The editor that answers checkpoints; until it exists, checkpoints go unanswered (the server asks again). */
  setSource: (source: SnapshotSource | null) => void
  /** Calls back once the server chose us to fill a new document — at once if it already did. */
  onSeed: (listener: () => void) => () => void
  destroy: () => void
}

/** ws://host/collab/issues in dev (Vite proxies it to :8081), wss:// behind HTTPS. */
export function collabBaseUrl(location: Location = window.location): string {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/collab/issues`
}

export function createTrakrProvider(
  issueKey: string,
  user: CollabUser,
  options: { url?: string; WebSocketPolyfill?: typeof WebSocket } = {},
): TrakrCollab {
  // BLOCK 2: the document and the connection. y-websocket builds the URL as base + '/' + room, so base
  // ".../collab/issues" and room "MAR-131" give /collab/issues/MAR-131. resyncInterval: every 30 s the client sends
  // syncStep1 again; the server answers with what it has, and the frame keeps an idle socket from timing out.
  const doc = new Y.Doc()
  const provider = new WebsocketProvider(options.url ?? collabBaseUrl(), issueKey, doc, {
    connect: true,
    resyncInterval: 30_000,
    ...(options.WebSocketPolyfill ? { WebSocketPolyfill: options.WebSocketPolyfill } : {}),
  })
  let source: SnapshotSource | null = null
  let seedRequested = false
  const seedListeners = new Set<() => void>()

  // BLOCK 3: the checkpoint handshake (06-collab §4). Messages are handled in order, so while this runs every update
  // up to `upto` is already applied to `doc` (and, through Tiptap's binding, to the editor): the snapshot really
  // contains them. Whatever the handler writes into `encoder` is sent back on the same socket.
  provider.messageHandlers[messageCheckpoint] = (encoder, decoder) => {
    const upto = decoding.readVarUint(decoder)
    if (source === null) {
      return
    }
    encoding.writeVarUint(encoder, messageSnapshot)
    encoding.writeVarUint(encoder, upto)
    encoding.writeVarUint8Array(encoder, Y.encodeStateAsUpdate(doc))
    encoding.writeVarString(encoder, source.getText())
    encoding.writeVarString(encoder, source.getHTML())
  }

  provider.messageHandlers[messageSeed] = () => {
    seedRequested = true
    for (const listener of seedListeners) {
      listener()
    }
  }

  // BLOCK 4: who we are, for everyone else's carets (the CollaborationCaret extension reads `user`)
  provider.awareness.setLocalStateField('user', user)

  return {
    doc,
    provider,
    setSource: (next) => {
      source = next
    },
    onSeed: (listener) => {
      seedListeners.add(listener)
      if (seedRequested) {
        listener()
      }
      return () => seedListeners.delete(listener)
    },
    // BLOCK 5: destroy — closes the socket (the server announces us gone), stops the timers, frees the document
    destroy: () => {
      seedListeners.clear()
      provider.destroy()
      doc.destroy()
    },
  }
}

/**
 * The provider as an external store for useSyncExternalStore: subscribing connects, the last unsubscribe
 * disconnects. Creating it is pure (no socket, no timers), so React may call the initializer twice in StrictMode
 * without leaking a connection; the socket opens only when a component is really on screen.
 */
export function collabStore(issueKey: string, user: CollabUser) {
  let collab: TrakrCollab | null = null
  let subscribers = 0
  return {
    // arrow functions: React calls these detached from the object, so they mustn't depend on `this`
    subscribe: (onChange: () => void) => {
      subscribers += 1
      if (collab === null) {
        collab = createTrakrProvider(issueKey, user)
        onChange()
      }
      return () => {
        subscribers -= 1
        if (subscribers === 0 && collab !== null) {
          collab.destroy()
          collab = null
          onChange()
        }
      }
    },
    get: (): TrakrCollab | null => collab,
  }
}

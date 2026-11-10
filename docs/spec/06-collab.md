# 06 · Collaborative description editing

## 1. The idea in five sentences

1. Each issue description is a **Yjs document** (a CRDT): every edit becomes a small binary _update_.
2. Updates can be applied **in any order and more than once** and every copy converges to the same text. That property is what lets a dumb server work.
3. The **collab** app (Java) never decodes document content. It authenticates, **stores** updates, **replays** them to newcomers and **relays** them to peers.
4. Clients periodically send a **snapshot** (the whole document as one update, plus plain text and HTML) so the server can delete old updates and the api can index the text.
5. **Awareness** (who is here, where their caret is) rides on the same socket and is never stored.

## 2. Frontend pieces

- Tiptap 3 editor with `Collaboration` (binds ProseMirror to `ydoc.getXmlFragment('description')`) and `CollaborationCaret` (shows other users' carets with their VOLT avatar colour).
- `y-websocket`'s `WebsocketProvider` (standard protocol, §3) connected to `/collab/issues/{issueKey}`; we add one custom message handler (type 101, §4).
- Undo/redo comes from the collaboration extension (only your own changes are undone).
- Offline: edits made while disconnected are kept in the Y.Doc and sent on reconnect. Optional: `y-indexeddb` to survive page reloads (phase 16 stretch goal).

## 3. Wire protocol (y-websocket / y-protocols) — what the Java relay must speak

All messages are **binary WebSocket frames** encoded with lib0 encoding:

- `varUint`: unsigned integer, 7 bits per byte, little-endian groups; high bit `0x80` = "more bytes follow". 300 → `0xAC 0x02`.
- `varUint8Array`: `varUint length` followed by that many bytes.
- `varString`: `varUint8Array` of UTF-8 bytes.

| First varUint (message type) | Second varUint (sub-type) | Payload                                                                   | Meaning                                                                      |
| ---------------------------- | ------------------------- | ------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| 0 `sync`                     | 0 `syncStep1`             | varUint8Array state vector                                                | "Here is what I have; send me what I'm missing"                              |
| 0 `sync`                     | 1 `syncStep2`             | varUint8Array update                                                      | "Here is what you were missing" — receiving this marks the client **synced** |
| 0 `sync`                     | 2 `update`                | varUint8Array update                                                      | A live edit                                                                  |
| 1 `awareness`                | —                         | varUint8Array awareness update                                            | Presence/caret changes                                                       |
| 3 `queryAwareness`           | —                         | —                                                                         | "Send me everyone's awareness"                                               |
| 101 `trakrCheckpoint` (ours) | —                         | varUint uptoUpdateId                                                      | Server asks this client for a snapshot (§4)                                  |
| 102 `trakrSnapshot` (ours)   | —                         | varUint uptoUpdateId, varUint8Array state, varString text, varString html | Client's reply                                                               |
| 103 `trakrSeed` (ours)       | —                         | —                                                                         | Server → one member: "this document is new, fill it from the issue's HTML"  |

`trakrSeed` (added in phase 16): an issue whose description was written before collaborative editing has no Yjs document. The first member to join a room with nothing stored (no snapshot, no updates) gets this message after step 3; if its document is still empty it calls `editor.commands.setContent(issue.descriptionHtml)`, which becomes an ordinary update for everyone else. Exactly one client per room is chosen (released again if it leaves before anything was stored), so the old description is never inserted twice.

Awareness update payload: `varUint count`, then per entry `varUint clientId`, `varUint clock`, `varString json` (`"null"` means "this client left").

### Server behaviour per connection

On open (after auth, §5):

1. Send `sync/syncStep1` with an **empty state vector** (one byte `0x00`). The client answers with `syncStep2` containing _everything it has_ — this is how offline edits reach the server. Treat that payload as an ordinary update (store + relay) unless it is the empty update `0x00 0x00`.
2. Load `collab_snapshot.state` (or the empty update `0x00 0x00` if none) and send it as `sync/syncStep2` → the client becomes _synced_.
3. Send every `collab_update` with `id > snapshot.upto_update_id`, in id order, as `sync/update`.
4. Send `awareness` for all other connected clients in this room.
5. If the stored updates reach past the snapshot (everyone left before a checkpoint, or collab restarted) and the newcomer is a member, schedule a checkpoint (§4) — so the description reaches the api without waiting for the next edit.

On message:

- `sync/syncStep1` from client → reply exactly as steps 2–3 (clients send this on reconnect).
- `sync/syncStep2` or `sync/update` → reject if the user is a viewer; else `INSERT collab_update` (returns id), relay to other local sockets in the room, publish `trakr.collab.update.v1` for other instances, record `lastUpdateId` on the room.
- `awareness` → decode the client ids/clocks it mentions (remember them for this connection), relay to others unchanged.
- `queryAwareness` → send all known awareness states.
- `trakrSnapshot` → §4.
- Anything else, or a frame > 1 MiB → close with 1003/1009.

On close: broadcast an awareness update setting each of the connection's client ids to `null` (clock + 1).

## 4. Snapshots and compaction (the checkpoint handshake)

Why a handshake: the server only knows a client has _applied_ earlier updates when the client handles a later message, because WebSocket messages are processed in order.

1. The room schedules a checkpoint when edits go idle for 2 s, or every 200 updates.
2. Server picks one connected **member** (not viewer) and sends `trakrCheckpoint(uptoUpdateId = room.lastUpdateId)`.
3. That client, while handling the message (so everything earlier is already applied), replies `trakrSnapshot(uptoUpdateId, Y.encodeStateAsUpdate(ydoc), editor.getText(), editor.getHTML())`.
4. Server, in one transaction: upsert `collab_snapshot(state, upto_update_id)`, `DELETE FROM collab_update WHERE issue_id = ? AND id <= upto`. Then publish `trakr.collab.snapshot.v1 {issueId, text, html, uptoUpdateId}`.
5. api (`api-collab-snapshots` consumer) sanitises the HTML, updates `issue.description_html/text`, bumps `version`, records history (debounced: one `field_changed` history row per user per 10 minutes), emits `trakr.issue.description-changed.v1` → search reindexes.

Late-arriving or duplicated snapshot with `upto ≤ current snapshot.upto` → ignore.

## 5. Security

- The WebSocket handshake carries the `TRAKR_SESSION` cookie. collab reads the session from the **same Redis** (Spring Session) — no tokens in URLs.
  At every login the api stores the user id as the plain string attribute `FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME`. collab reads only `lastAccessedTime`, `maxInactiveInterval` and that attribute from the session hash (`trakr:session:sessions:<id>`, the cookie value is the id in base64), deserialising them with an `ObjectInputFilter` that admits String/Long/Integer only. It never loads the SecurityContext: that would need the api's `TrakrPrincipal`, Spring Security and OAuth2 classes on collab's classpath.
- Origin check: allow only the configured app origin(s).
- After auth, check `project_member` for the issue's project (read-only query). Viewers get read-only rooms: their update frames are dropped and they never receive checkpoints.
- Per-connection limits: 30 update frames/s, 1 MiB per frame, 50 connections per user.
- Idle socket closed after 10 minutes without frames (clients ping every 30 s via the provider's resync interval).

## 6. Scaling across collab instances

- A room lives in memory per instance: `issueId → {sockets, awarenessByClientId, lastUpdateId}`.
- Updates are relayed locally and published to `trakr.collab.v1` (key issueId). Every collab instance consumes the topic with its own group; it relays to its local room unless `originInstance` equals itself.
- Storage happens only on the instance that received the frame, so there is exactly one row per update.
- Awareness is relayed the same way (type `trakr.collab.awareness.v1`, not stored).
- Checkpoints: only the instance with the **lowest instance id** that has a member connected in the room schedules them (instances announce themselves in Redis `collab:room:{issueId}` set with TTL heartbeats).

## 7. Java design (apps/collab)

- Spring WebSocket (`spring-boot-starter-websocket`), a `BinaryWebSocketHandler` per room path `/collab/issues/{issueKey}` registered via `WebSocketConfigurer`.
- A `HandshakeInterceptor` resolves the session and project membership and stores `userId`, `role`, `issueId` in the session attributes.
- `Lib0Decoder` / `Lib0Encoder` — your own tiny classes over `ByteBuffer`/`ByteArrayOutputStream` for varUint, varUint8Array, varString. **Unit-test them against bytes produced by the JS library** (fixtures generated once with a Node script in `apps/collab/src/test/resources/fixtures/`).
- `RoomRegistry` (`ConcurrentHashMap<UUID, Room>`), `Room` (sockets, awareness, lastUpdateId; sending is serialised per socket with `ConcurrentWebSocketSessionDecorator`).
- `CollabStore` (JdbcClient): `appendUpdate`, `loadSnapshot`, `loadUpdatesAfter`, `saveSnapshotAndCompact`.
- `CollabRelay` (Kafka producer/consumer).
- Virtual threads for blocking DB calls in handlers.

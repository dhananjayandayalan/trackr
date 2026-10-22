# Phase 16 · Collaborative description editing (Yjs + Java WebSocket relay)

**Goal:** two people edit an issue description at the same time, see each other's carets, and the result is stored, compacted and searchable — with a Java server that relays CRDT updates without understanding them.
**Spec:** 06-collab (all), 04-events (collab topic), 02-data-model §5. **Time:** 6–7 days.

## Concepts you'll learn

- **CRDTs** — why concurrent edits converge without a central lock.
- **State vectors and updates** — what Yjs exchanges.
- **Binary protocols** — varints, length-prefixed bytes, reading/writing with `ByteBuffer`.
- **WebSockets in Spring** — handlers, handshake interceptors, per-session send serialisation.
- **Rooms and fan-out across instances** via Kafka.
- **The checkpoint handshake** — using in-order delivery as a synchronisation point.

## Backend — `apps/collab`

0. api migration `V012__collab.sql` (collab_update, collab_snapshot — 02-data-model §5)
1. `settings.gradle.kts` → `include("apps:collab")`
2. `apps/collab/build.gradle.kts`: spring-app plugin; WebSocket starter; JDBC starter + postgresql; Spring Session Data Redis + Data Redis (read the shared session); Spring Kafka; Actuator; contracts
3. `CollabApplication.java`, `application.yml` (port 8081, same Redis session namespace as api, `trakr.app.origin`)
4. `protocol/Lib0Decoder.java`, `protocol/Lib0Encoder.java` + tests against fixtures
5. `protocol/Messages.java` — constants and small records for parsed messages (`SyncStep1`, `SyncStep2`, `Update`, `Awareness`, `QueryAwareness`, `TrakrSnapshot`) + `MessageReader` (bytes → message) and `MessageWriter` (message → bytes)
6. `protocol/AwarenessCodec.java` — decode client ids/clocks; encode "client left" (`null` state)
7. `security/SessionAuthHandshakeInterceptor.java` — resolve `TRAKR_SESSION` via `FindByIndexNameSessionRepository`/`SessionRepository`, read the `SecurityContext` from session attributes, check origin, load issue + membership via `JdbcClient`
8. `store/CollabStore.java` (`appendUpdate`, `loadSnapshot`, `loadUpdatesAfter`, `saveSnapshotAndCompact`)
9. `room/Room.java`, `room/RoomRegistry.java`, `room/CheckpointScheduler.java`
10. `ws/CollabWebSocketHandler.java` (`BinaryWebSocketHandler`) + `ws/WebSocketConfig.java` (`/collab/issues/{issueKey}`, allowed origins, max binary message size 1 MiB)
11. `relay/CollabRelay.java` (produce/consume `trakr.collab.v1`, own random group id, skip own `originInstance`)
12. api: `issue/internal/DescriptionSnapshotConsumer.java` (`api-collab-snapshots` group) → sanitise, update issue, history (debounced), publish `IssueDescriptionChanged`
13. `apps/collab/src/test/node/make-fixtures.mjs` — **one-off** Node script (uses `yjs`, `y-protocols`, `lib0`) that writes `.bin` files for: varUint edge values (0, 127, 128, 300, 2^32), a syncStep1, a syncStep2, an update, an awareness update with two clients, a "client left" awareness. Run once, commit the `.bin` files.

### File 4 — `Lib0Decoder`

| #   | Cue                             | What                                                                           |
| --- | ------------------------------- | ------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: state`             | wraps a `ByteBuffer`; `hasRemaining()`                                         |
| 2   | `// BLOCK 2: readVarUint`       | loop: read byte `b`; `value                                                    | = (b & 0x7F) << shift`; `shift += 7`; stop when `(b & 0x80) == 0`; use `long` and reject > 53 bits (JS number safety) |
| 3   | `// BLOCK 3: readVarUint8Array` | length = `readVarUint`, then that many bytes (bounds-check against remaining!) |
| 4   | `// BLOCK 4: readVarString`     | UTF-8 decode of `readVarUint8Array`                                            |

Encoder mirrors it. **Test first** with the fixtures: decoding the JS-produced bytes must give the original numbers/strings; encoding must reproduce the exact bytes.
**Ask yourself:** why is the length check in block 3 a security issue and not just a bug?

### File 10 — `CollabWebSocketHandler`

| #   | Cue                                        | What                                                                                                                                                                                                                                |
| --- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: afterConnectionEstablished`   | wrap the session in `ConcurrentWebSocketSessionDecorator` (send time limit 5 s, buffer 4 MiB); join room; send syncStep1(empty) → syncStep2(snapshot or empty update) → stored updates → current awareness (06-collab §3 "On open") |
| 2   | `// BLOCK 2: handleBinaryMessage dispatch` | `switch (MessageReader.read(bytes))` over the sealed message records                                                                                                                                                                |
| 3   | `// BLOCK 3: updates`                      | viewer → drop; else `store.appendUpdate` → `room.broadcast(except sender)` → `relay.publish` → `room.noteUpdate(id)` → `checkpointScheduler.touch(room)`                                                                            |
| 4   | `// BLOCK 4: awareness`                    | remember client ids for this session; relay unchanged                                                                                                                                                                               |
| 5   | `// BLOCK 5: snapshot reply`               | validate `upto` ≤ room.lastUpdateId; `store.saveSnapshotAndCompact`; publish `trakr.collab.snapshot.v1`                                                                                                                             |
| 6   | `// BLOCK 6: afterConnectionClosed`        | broadcast "left" awareness for this session's client ids; leave room; drop empty rooms                                                                                                                                              |

All blocking work (DB) runs on virtual threads — enable `spring.threads.virtual.enabled` and check which thread handles messages.

## Frontend

14. `pnpm add @tiptap/react @tiptap/pm @tiptap/starter-kit @tiptap/extension-collaboration @tiptap/extension-collaboration-caret yjs y-websocket`
15. `src/features/collab/trakr-provider.ts`
    | # | What |
    |---|---|
    | 1 | `new Y.Doc()`; `new WebsocketProvider(wsBase + '/collab/issues', issueKey, ydoc, { connect: true })` — note how y-websocket builds the URL from base + room name; make it produce `/collab/issues/MAR-131` |
    | 2 | register `provider.messageHandlers[101] = (encoder, decoder, provider) => { … }` that reads `uptoUpdateId` and sends message 102 with `Y.encodeStateAsUpdate(ydoc)`, `editor.getText()`, `editor.getHTML()` (use `lib0/encoding`) |
    | 3 | `provider.awareness.setLocalStateField('user', { name, color })` using the VOLT avatar colour |
    | 4 | `destroy()` → provider + doc |
16. `src/features/collab/DescriptionEditor.tsx` — lazy-loaded (`React.lazy`) so Tiptap/Yjs land in the `editor` chunk; `useEditor({ extensions: [StarterKit.configure({ undoRedo: false }), Collaboration.configure({ document: ydoc, field: 'description' }), CollaborationCaret.configure({ provider, user })] })`; read-only for viewers; "Saved" indicator when the last checkpoint was acknowledged (listen for the api SSE `issue.updated` with `description` change)
17. `DescriptionEditor.module.css` — editor content styles inside `@scope (.editor)`; carets and name labels use avatar tokens

## Tests

- `Lib0CodecTest` (fixtures), `MessageReaderTest`, `AwarenessCodecTest`.
- `CollabStoreIT` — append, load-after, snapshot + compaction in one transaction.
- `CollabHandlerIT` — start the app on a random port; connect two `StandardWebSocketClient`s with a valid session cookie (create one in Redis in the test); client A sends an update fixture → client B receives identical bytes; new client C receives snapshot + updates on join; viewer's update is dropped.
- Playwright (two contexts): both type into the same description at the same time → both end with identical text containing both edits; reload → text persists; search finds a word typed 3 s earlier.

## Done when

- Two browsers co-edit with live carets; killing and restarting collab loses nothing; `collab_update` shrinks after checkpoints.
- You can explain, using the varUint bytes you decoded by hand, what a syncStep2 message contains.

Commit: `phase-16: collaborative editing`.

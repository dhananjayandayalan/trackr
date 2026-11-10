// One-off fixture generator (phase 16, 10-testing §2). The Java relay speaks a protocol defined by JavaScript
// libraries, so its tests compare against bytes those libraries really produce — not against bytes we think they
// produce. Run once, commit the .bin files; run again only when the libraries' versions change:
//
//   cd backend/apps/collab/src/test/node && pnpm install && node make-fixtures.mjs
//
// Writes ../resources/fixtures/*.bin and manifest.json (the values the Java tests expect to decode).
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as encoding from 'lib0/encoding'
import * as awarenessProtocol from 'y-protocols/awareness'
import * as syncProtocol from 'y-protocols/sync'
import * as Y from 'yjs'

const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'resources', 'fixtures')
mkdirSync(out, { recursive: true })
const manifest = {}

function write(name, bytes) {
  writeFileSync(join(out, name), bytes)
  console.log(`${name.padEnd(28)} ${bytes.length} bytes  ${Buffer.from(bytes.slice(0, 16)).toString('hex')}`)
}

function message(build) {
  const encoder = encoding.createEncoder()
  build(encoder)
  return encoding.toUint8Array(encoder)
}

// BLOCK 1: the primitives — varUint edge values (1 byte, the 1→2 byte boundary, the spec's example, past 32 bits)
manifest.varUints = [0, 127, 128, 300, 2 ** 32]
for (const value of manifest.varUints) {
  write(`varuint-${value}.bin`, message((e) => encoding.writeVarUint(e, value)))
}
manifest.varString = 'Grüße aus MAR-131 ✓'
write('varstring.bin', message((e) => encoding.writeVarString(e, manifest.varString)))

// BLOCK 2: a document shaped like Tiptap's (a paragraph in the 'description' fragment). Fixed client id, so the
// bytes are the same on every run.
const doc = new Y.Doc()
doc.clientID = 1001
const fragment = doc.getXmlFragment('description')
const paragraph = new Y.XmlElement('paragraph')
paragraph.insert(0, [new Y.XmlText('Hello')])
fragment.insert(0, [paragraph])

// the raw Yjs payloads, so the tests can check a message carries exactly these bytes
write('doc-state-vector.bin', Y.encodeStateVector(doc))
write('doc-state.bin', Y.encodeStateAsUpdate(doc))

// BLOCK 3: the sync messages, written by y-protocols the way y-websocket sends them (type 0, then the step)
write('sync-step1.bin', message((e) => {
  encoding.writeVarUint(e, 0)
  syncProtocol.writeSyncStep1(e, doc)
}))
write('sync-step2.bin', message((e) => {
  encoding.writeVarUint(e, 0)
  syncProtocol.writeSyncStep2(e, doc)
}))

// a live edit: the update Yjs emits for one insertion
let live
doc.on('update', (update) => {
  live = update
})
paragraph.get(0).insert(5, ' world')
write('update-payload.bin', live)
write('update.bin', message((e) => {
  encoding.writeVarUint(e, 0)
  syncProtocol.writeUpdate(e, live)
}))

// BLOCK 4: awareness with two clients, then one of them leaving
const docB = new Y.Doc()
docB.clientID = 2002
const ada = new awarenessProtocol.Awareness(doc)
const grace = new awarenessProtocol.Awareness(docB)
ada.setLocalState({ user: { name: 'Ada Lovelace', avatar: 'violet' } })
grace.setLocalState({ user: { name: 'Grace Hopper', avatar: 'cyan' } })
awarenessProtocol.applyAwarenessUpdate(ada, awarenessProtocol.encodeAwarenessUpdate(grace, [2002]), 'test')
write('awareness-two-clients.bin', message((e) => {
  encoding.writeVarUint(e, 1)
  encoding.writeVarUint8Array(e, awarenessProtocol.encodeAwarenessUpdate(ada, [1001, 2002]))
}))
manifest.awarenessTwoClients = [1001, 2002].map((clientId) => ({
  clientId,
  clock: ada.meta.get(clientId).clock,
  json: JSON.stringify(ada.getStates().get(clientId)),
}))

awarenessProtocol.removeAwarenessStates(ada, [2002], 'test')
write('awareness-left.bin', message((e) => {
  encoding.writeVarUint(e, 1)
  encoding.writeVarUint8Array(e, awarenessProtocol.encodeAwarenessUpdate(ada, [2002]))
}))
manifest.awarenessLeft = [{ clientId: 2002, clock: ada.meta.get(2002).clock, json: 'null' }]

// BLOCK 5: our own messages, encoded exactly as trakr-provider.ts does with lib0
manifest.checkpoint = { uptoUpdateId: 42 }
write('checkpoint.bin', message((e) => {
  encoding.writeVarUint(e, 101)
  encoding.writeVarUint(e, 42)
}))
manifest.snapshot = { uptoUpdateId: 42, text: 'Hello world', html: '<p>Hello world</p>' }
write('snapshot.bin', message((e) => {
  encoding.writeVarUint(e, 102)
  encoding.writeVarUint(e, 42)
  encoding.writeVarUint8Array(e, Y.encodeStateAsUpdate(doc))
  encoding.writeVarString(e, manifest.snapshot.text)
  encoding.writeVarString(e, manifest.snapshot.html)
}))
write('snapshot-state.bin', Y.encodeStateAsUpdate(doc))

writeFileSync(join(out, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
console.log('manifest.json written')

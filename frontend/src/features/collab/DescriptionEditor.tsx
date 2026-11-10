import { useSuspenseQuery } from '@tanstack/react-query'
import Collaboration from '@tiptap/extension-collaboration'
import CollaborationCaret from '@tiptap/extension-collaboration-caret'
import { EditorContent, useEditor } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import type * as Y from 'yjs'
import { meQuery } from '@/features/auth/queries.ts'
import type { IssueDetails } from '@/shared/api/types.ts'
import type { AvatarColor } from '@/shared/ui/Avatar.tsx'
import { SafeHtml } from '@/shared/ui/SafeHtml.tsx'
import { collabStore, descriptionField, type CollabUser, type TrakrCollab } from './trakr-provider.ts'
import styles from './DescriptionEditor.module.css'

/**
 * The issue's description as a shared document (06-collab §2): everyone with the issue open types into the same
 * Yjs document and sees the others' carets. Loaded with React.lazy from IssueView, so Tiptap, ProseMirror and Yjs
 * sit in the `editor` chunk and only download when an issue opens.
 *
 * Read-only for viewers: they still see the others type, live; the server would drop their edits anyway.
 */
export function DescriptionEditor({ issue, canEdit }: { issue: IssueDetails; canEdit: boolean }) {
  const { data: me } = useSuspenseQuery(meQuery())
  const user: CollabUser = { name: me.displayName, avatar: me.avatarColor }

  // BLOCK 1: the connection, as an external store — on screen = connected (see collabStore)
  const [store] = useState(() => collabStore(issue.key, user))
  const collab = useSyncExternalStore(store.subscribe, store.get)

  if (collab === null) {
    return <Fallback html={issue.descriptionHtml} />
  }
  return <CollabEditor collab={collab} issue={issue} canEdit={canEdit} user={user} />
}

function Fallback({ html }: { html: string | null | undefined }) {
  return html ? <SafeHtml html={html} /> : <p className={styles.empty}>No description.</p>
}

type SaveState = 'idle' | 'saving' | 'saved' | 'offline'

function CollabEditor({
  collab,
  issue,
  canEdit,
  user,
}: {
  collab: TrakrCollab
  issue: IssueDetails
  canEdit: boolean
  user: CollabUser
}) {
  // BLOCK 2: the editor. StarterKit without the features the server's allow-list would strip anyway (headings,
  // quotes, rules, strike, underline: 03-api §3.5), and without its own undo — Collaboration brings an undo that
  // only undoes your own changes, not your neighbour's.
  const editor = useEditor({
    editable: canEdit,
    extensions: [
      StarterKit.configure({
        undoRedo: false,
        heading: false,
        blockquote: false,
        horizontalRule: false,
        strike: false,
        underline: false,
        link: { openOnClick: !canEdit, autolink: true },
      }),
      Collaboration.configure({ document: collab.doc, field: descriptionField }),
      CollaborationCaret.configure({ provider: collab.provider, user, render: renderCaret, selectionRender }),
    ],
    editorProps: {
      attributes: {
        class: styles.content ?? '',
        role: 'textbox',
        'aria-multiline': 'true',
        'aria-label': 'Description',
        'aria-readonly': String(!canEdit),
      },
    },
  })

  // BLOCK 3: answer checkpoints with this editor's text and HTML; fill a brand-new document from the old description
  useEffect(() => {
    collab.setSource(editor)
    const html = issue.descriptionHtml
    const stopSeed = collab.onSeed(() => {
      const fragment: Y.XmlFragment = collab.doc.getXmlFragment(descriptionField)
      // empty: nothing from this browser's offline edits either. Only one client gets the seed message.
      if (html && fragment.length === 0) {
        editor.commands.setContent(html)
      }
    })
    return () => {
      collab.setSource(null)
      stopSeed()
    }
    // the seed reads the description once, when the document is created — later changes come through Yjs
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collab, editor])

  const saveState = useSaveState(collab, issue.version)

  return (
    <div className={styles.editor} data-editable={canEdit}>
      <EditorContent editor={editor} />
      <p className={styles.status} aria-live="polite" data-state={saveState}>
        {saveLabel[saveState]}
      </p>
    </div>
  )
}

const saveLabel: Record<SaveState, string> = {
  idle: '',
  saving: 'Saving…',
  saved: 'Saved',
  offline: 'Offline · your changes sync when the connection is back',
}

/** After the server stored a snapshot, the api applies it and its SSE event refetches the issue: version goes up. */
const QUIET_MS = 8_000

/**
 * BLOCK 4: the "Saved" indicator. Saving: we typed since the issue's version last went up. Saved: it went up after
 * our last edit — the api stored a snapshot that includes it. A snapshot that changes nothing (typed, then undone)
 * bumps no version, so a quiet connected period counts as saved too.
 */
function useSaveState(collab: TrakrCollab, version: number): SaveState {
  const [editedAt, setEditedAt] = useState<number | null>(null)
  const [quiet, setQuiet] = useState(false)
  const [online, setOnline] = useState(collab.provider.wsconnected)
  const versionRef = useRef(version)

  useEffect(() => {
    versionRef.current = version
  }, [version])

  useEffect(() => {
    // remote updates come in with the provider as their origin; everything else was typed here
    const onUpdate = (_update: Uint8Array, origin: unknown) => {
      if (origin !== collab.provider) {
        setEditedAt(versionRef.current)
        setQuiet(false)
      }
    }
    const onStatus = ({ status }: { status: string }) => {
      setOnline(status === 'connected')
    }
    collab.doc.on('update', onUpdate)
    collab.provider.on('status', onStatus)
    return () => {
      collab.doc.off('update', onUpdate)
      collab.provider.off('status', onStatus)
    }
  }, [collab])

  useEffect(() => {
    if (editedAt === null) {
      return
    }
    const timer = setTimeout(() => {
      setQuiet(true)
    }, QUIET_MS)
    return () => {
      clearTimeout(timer)
    }
  }, [editedAt])

  if (editedAt === null) {
    return 'idle'
  }
  if (!online) {
    return 'offline'
  }
  return version > editedAt || quiet ? 'saved' : 'saving'
}

// BLOCK 5: other people's carets and selections, in their avatar colour. The user object comes from their browser
// (awareness), so its colour is a name checked against our list, never a CSS value we'd paste into a style. Tiptap
// guards the same way: any `color` that isn't #rrggbb reaches us as "transparent".
const avatarColors = new Set<string>([
  'pink',
  'cyan',
  'lime',
  'yellow',
  'orange',
  'violet',
] satisfies AvatarColor[])

function caretColor(user: Record<string, unknown>): string {
  const avatar = typeof user.avatar === 'string' ? user.avatar : ''
  return avatarColors.has(avatar) ? `var(--avatar-${avatar})` : 'var(--text-muted)'
}

function renderCaret(user: Record<string, unknown>): HTMLElement {
  const caret = document.createElement('span')
  caret.className = styles.caret ?? ''
  caret.style.setProperty('--caret-color', caretColor(user))
  const label = document.createElement('span')
  label.className = styles.caretLabel ?? ''
  // textContent, not innerHTML: a name is text whatever characters it contains
  label.textContent = typeof user.name === 'string' ? user.name : 'Someone'
  caret.append(label)
  return caret
}

function selectionRender(user: Record<string, unknown>) {
  return { class: styles.selection ?? '', style: `--caret-color: ${caretColor(user)}` }
}

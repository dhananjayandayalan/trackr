import { useQuery } from '@tanstack/react-query'
import {
  useEffect,
  useId,
  useRef,
  useState,
  useTransition,
  type ChangeEvent,
  type CSSProperties,
  type KeyboardEvent,
  type SubmitEvent,
} from 'react'
import { projectQuery } from '@/features/projects/queries.ts'
import { messageOf } from '@/shared/api/errors.ts'
import type { MemberView } from '@/shared/api/types.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { Button } from '@/shared/ui/Button.tsx'
import { FormAlert } from '@/shared/ui/Form.tsx'
import { Kbd } from '@/shared/ui/Kbd.tsx'
import { Textarea } from '@/shared/ui/TextInput.tsx'
import { mentionQueryAt, toCommentHtml, type Mention } from './comment-html.ts'
import styles from './CommentComposer.module.css'

interface CommentComposerProps {
  projectKey: string
  label: string
  submitLabel: string
  initialText?: string
  initialMentions?: Mention[]
  /** gets the comment as HTML; throw to keep the text and show the error */
  onSubmit: (bodyHtml: string) => Promise<unknown>
  onCancel?: () => void
}

/**
 * The comment box: a plain textarea that grows with its text (field-sizing: content), plus a mention picker.
 * Typing "@" and a few letters opens a list of the project's people; picking one writes "@Name" and remembers
 * who it was, so the HTML sent to the api carries their id (comment-html.ts). v1 anchors the list to the
 * textarea itself rather than to the caret: CSS can't anchor to a text position inside a textarea.
 */
export function CommentComposer({
  projectKey,
  label,
  submitLabel,
  initialText = '',
  initialMentions = [],
  onSubmit,
  onCancel,
}: CommentComposerProps) {
  const id = useId()
  const area = useRef<HTMLTextAreaElement>(null)
  const list = useRef<HTMLUListElement>(null)
  const { data: project } = useQuery(projectQuery(projectKey))
  const [text, setText] = useState(initialText)
  const [mentions, setMentions] = useState<Mention[]>(initialMentions)
  const [picker, setPicker] = useState<{ query: string; start: number } | null>(null)
  const [active, setActive] = useState(0)
  const [error, setError] = useState<string>()
  const [pending, startTransition] = useTransition()
  const anchor = { '--_anchor': `--composer-${id.replace(/[^a-zA-Z0-9]/g, '-')}` } as CSSProperties

  // BLOCK 1: who can be mentioned — everyone who can see the project (the api drops anyone else anyway)
  const candidates: MemberView[] = picker
    ? (project?.members ?? [])
        .filter((member) => member.displayName.toLowerCase().includes(picker.query.toLowerCase()))
        .slice(0, 6)
    : []
  const listOpen = candidates.length > 0

  // BLOCK 2: state → popover. A manual popover: it stays open while the user types (an auto popover would
  // close on the first keystroke outside it), and our code alone opens and closes it.
  useEffect(() => {
    const element = list.current
    if (!element || typeof element.showPopover !== 'function') {
      return
    }
    const shown = element.matches(':popover-open')
    if (listOpen && !shown) element.showPopover()
    if (!listOpen && shown) element.hidePopover()
  }, [listOpen])

  const onChange = (event: ChangeEvent<HTMLTextAreaElement>) => {
    setText(event.target.value)
    setPicker(mentionQueryAt(event.target.value, event.target.selectionStart))
    setActive(0)
  }

  // BLOCK 3: picking someone replaces "@que" with "@Full Name " and puts the caret after it
  const choose = (member: MemberView) => {
    if (!picker) return
    const caret = area.current?.selectionStart ?? text.length
    const inserted = `@${member.displayName} `
    const next = text.slice(0, picker.start) + inserted + text.slice(caret)
    setText(next)
    setMentions((current) =>
      current.some((m) => m.id === member.userId)
        ? current
        : [...current, { id: member.userId, name: member.displayName }],
    )
    setPicker(null)
    const position = picker.start + inserted.length
    requestAnimationFrame(() => {
      area.current?.focus()
      area.current?.setSelectionRange(position, position)
    })
  }

  const submit = (event?: SubmitEvent) => {
    event?.preventDefault()
    if (!text.trim() || pending) return
    // only the people still named in the text: a picked mention that was deleted again doesn't count
    const html = toCommentHtml(
      text,
      mentions.filter((m) => text.includes(`@${m.name}`)),
    )
    setError(undefined)
    startTransition(async () => {
      try {
        await onSubmit(html)
        setText('')
        setMentions([])
      } catch (failure) {
        setError(messageOf(failure))
      }
    })
  }

  // BLOCK 4: keyboard — the list's arrows/Enter/Esc while it's open; Ctrl/Cmd+Enter sends
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (listOpen) {
      const move = { ArrowDown: 1, ArrowUp: -1 }[event.key]
      if (move !== undefined) {
        event.preventDefault()
        setActive((index) => (index + move + candidates.length) % candidates.length)
        return
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault()
        const member = candidates[active]
        if (member) choose(member)
        return
      }
      if (event.key === 'Escape') {
        // close the list, not the side panel around us
        event.preventDefault()
        event.stopPropagation()
        setPicker(null)
        return
      }
    }
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault()
      submit()
    }
  }

  const optionId = (index: number) => `${id}-option-${String(index)}`

  return (
    <form className={styles.root} onSubmit={submit} aria-label={label} style={anchor}>
      <FormAlert message={error} />
      <Textarea
        ref={area}
        className={styles.area}
        value={text}
        onChange={onChange}
        onKeyDown={onKeyDown}
        onBlur={() => setPicker(null)}
        placeholder="Add a comment… type @ to mention someone"
        aria-label={label}
        maxLength={10_000}
        // a textarea can't be a combobox (no aria-expanded on it, axe: aria-allowed-attr); as a textbox it may
        // still point at the list it controls and at the option the arrow keys picked
        aria-autocomplete="list"
        aria-controls={`${id}-mentions`}
        aria-activedescendant={listOpen ? optionId(active) : undefined}
      />
      <ul
        ref={list}
        id={`${id}-mentions`}
        popover="manual"
        role="listbox"
        aria-label="People"
        className={styles.list}
      >
        {candidates.map((member, index) => (
          <li
            key={member.userId}
            id={optionId(index)}
            role="option"
            aria-selected={index === active}
            className={styles.option}
            // mousedown, not click: click comes after the textarea's blur, which would already have closed the list
            onMouseDown={(event) => {
              event.preventDefault()
              choose(member)
            }}
          >
            <Avatar
              name={member.displayName}
              initials={member.initials}
              color={avatarColor(member.avatarColor)}
              size="sm"
            />
            {member.displayName}
          </li>
        ))}
      </ul>
      <div className={styles.actions}>
        <span className={styles.hint}>
          <Kbd>Ctrl</Kbd> + <Kbd>Enter</Kbd> to send
        </span>
        {onCancel && (
          <Button variant="ghost" size="sm" onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Button type="submit" variant="primary" size="sm" disabled={pending || !text.trim()}>
          {pending ? 'Saving…' : submitLabel}
        </Button>
      </div>
    </form>
  )
}

import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  useDeferredValue,
  useEffect,
  useId,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from 'react'
import type { Completion } from '@/shared/api/types.ts'
import { completeQuery } from './queries.ts'
import type { TqlError } from './useSearch.ts'
import styles from './TqlEditor.module.css'

interface TqlEditorProps {
  value: string
  onChange: (value: string) => void
  /** Enter with no suggestion highlighted: run the query */
  onSubmit: (value: string) => void
  error?: TqlError | null
  label?: string
}

const kindLabel: Record<Completion['kind'], string> = {
  field: 'field',
  operator: 'operator',
  value: 'value',
  keyword: 'keyword',
}

/**
 * The query editor (00-product §3.11): a text input that is an ARIA combobox. The suggestions are a listbox in a
 * popover under the input; focus never leaves the input — aria-activedescendant tells a screen reader which
 * option the arrow keys are on, so typing and choosing are one continuous thing.
 */
export function TqlEditor({ value, onChange, onSubmit, error, label = 'Query' }: TqlEditorProps) {
  const id = useId()
  const listId = `${id}-list`
  const errorId = `${id}-error`
  const input = useRef<HTMLInputElement>(null)
  const list = useRef<HTMLUListElement>(null)
  const mirror = useRef<HTMLDivElement>(null)
  const pendingCaret = useRef<number | null>(null)
  const [caret, setCaret] = useState(value.length)
  const [focused, setFocused] = useState(false)
  const [dismissed, setDismissed] = useState(false)
  const [active, setActive] = useState(-1)

  // BLOCK 1: autocomplete — the text up to the caret, deferred: typing renders at once, the request for
  // suggestions follows when React has a moment (and a fast typist skips the in-between prefixes)
  const request = useDeferredValue({ tql: value.slice(0, caret), caret })
  const completions = useQuery({
    ...completeQuery(request.tql, request.caret),
    enabled: focused,
    placeholderData: keepPreviousData,
  })
  const from = Math.min(completions.data?.from ?? caret, caret)
  const word = value.slice(from, caret).toLowerCase()
  const options = (completions.data?.expected ?? [])
    .filter((option) => option.text.toLowerCase().startsWith(word) && option.text.toLowerCase() !== word)
    .slice(0, 12)
  const open = focused && !dismissed && options.length > 0
  const activeOption = active >= 0 && active < options.length ? active : -1

  // BLOCK 2: the popover follows `open` (showPopover puts it in the top layer; jsdom has neither, so guard)
  useEffect(() => {
    const element = list.current
    if (!element || typeof element.showPopover !== 'function') {
      return
    }
    const shown = element.matches(':popover-open')
    if (open && !shown) {
      element.showPopover()
    } else if (!open && shown) {
      element.hidePopover()
    }
  }, [open])

  // after a pick, the caret goes to the end of the inserted text (once React has written the new value)
  useEffect(() => {
    if (pendingCaret.current !== null && input.current) {
      input.current.setSelectionRange(pendingCaret.current, pendingCaret.current)
      setCaret(pendingCaret.current)
      pendingCaret.current = null
    }
  }, [value])

  function pick(option: Completion) {
    const next = `${value.slice(0, from)}${option.text} ${value.slice(caret).trimStart()}`
    pendingCaret.current = from + option.text.length + 1
    setActive(-1)
    onChange(next)
  }

  function trackCaret() {
    setCaret(input.current?.selectionStart ?? value.length)
  }

  // BLOCK 3: the keyboard — ↑/↓ move through the options (opening the list), Enter/Tab take one, Esc closes;
  // Enter with nothing highlighted runs the query
  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    const count = options.length
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowUp': {
        if (count === 0) {
          return
        }
        event.preventDefault()
        setDismissed(false)
        const step = event.key === 'ArrowDown' ? 1 : -1
        setActive((activeOption + step + count + (activeOption < 0 && step < 0 ? 1 : 0)) % count)
        return
      }
      case 'Enter': {
        event.preventDefault()
        const option = open ? options[activeOption] : undefined
        if (option) {
          pick(option)
        } else {
          setDismissed(true)
          onSubmit(value)
        }
        return
      }
      case 'Tab': {
        const option = open ? (options[activeOption] ?? (count === 1 ? options[0] : undefined)) : undefined
        if (option) {
          event.preventDefault()
          pick(option)
        }
        return
      }
      case 'Escape': {
        if (open) {
          event.preventDefault()
          setDismissed(true)
          setActive(-1)
        }
        return
      }
      default:
    }
  }

  // BLOCK 4: the error underline — a copy of the text behind the transparent input, with the part from the
  // error position to the end of that word in a <mark>. Same font and padding, so it lines up with the letters.
  const errorAt = error ? Math.min(error.position, value.length) : -1
  const errorEnd = errorAt < 0 ? -1 : Math.max(errorAt + 1, value.slice(errorAt).search(/\s|$/) + errorAt)
  const anchor = { '--_anchor': `--tql-${id.replace(/[^a-zA-Z0-9]/g, '-')}` } as CSSProperties

  return (
    <div className={styles.root} style={anchor}>
      <label htmlFor={id} className="visually-hidden">
        {label}
      </label>
      <div className={styles.field} data-invalid={error ? true : undefined}>
        {error && (
          <div ref={mirror} className={styles.mirror} aria-hidden="true">
            {value.slice(0, errorAt)}
            <mark className={styles.squiggle}>{value.slice(errorAt, errorEnd) || ' '}</mark>
            {value.slice(errorEnd)}
          </div>
        )}
        <input
          ref={input}
          id={id}
          type="text"
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={open && activeOption >= 0 ? `${listId}-${String(activeOption)}` : undefined}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          className={styles.input}
          value={value}
          spellCheck={false}
          autoComplete="off"
          placeholder="project = MAR AND status != Done ORDER BY priority DESC"
          onChange={(event) => {
            setDismissed(false)
            setActive(-1)
            setCaret(event.target.selectionStart ?? event.target.value.length)
            onChange(event.target.value)
          }}
          onKeyDown={onKeyDown}
          onKeyUp={trackCaret}
          onClick={trackCaret}
          onSelect={trackCaret}
          onScroll={(event) => {
            if (mirror.current) {
              mirror.current.scrollLeft = event.currentTarget.scrollLeft
            }
          }}
          onFocus={() => {
            setFocused(true)
          }}
          onBlur={() => {
            setFocused(false)
            setActive(-1)
          }}
        />
      </div>
      {error && (
        <p id={errorId} className={styles.error} role="alert">
          {error.message}
        </p>
      )}
      <ul
        id={listId}
        ref={list}
        role="listbox"
        aria-label="Suggestions"
        popover="manual"
        className={styles.list}
      >
        {options.map((option, index) => (
          <li
            key={`${option.kind}-${option.text}`}
            id={`${listId}-${String(index)}`}
            role="option"
            aria-selected={index === activeOption}
            className={styles.option}
            // mousedown, not click: a click would blur the input first and close the list under the pointer
            onMouseDown={(event) => {
              event.preventDefault()
              pick(option)
            }}
          >
            <span className={styles.text}>{option.text}</span>
            <span className={styles.kind}>{kindLabel[option.kind]}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

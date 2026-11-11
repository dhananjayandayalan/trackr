import { draggable, dropTargetForElements } from '@atlaskit/pragmatic-drag-and-drop/adapter/element-adapter'
import { combine } from '@atlaskit/pragmatic-drag-and-drop/utils/combine'
import { attachClosestEdge } from '@atlaskit/pragmatic-drag-and-drop-hitbox/closest-edge/attach-closest-edge'
import { extractClosestEdge } from '@atlaskit/pragmatic-drag-and-drop-hitbox/closest-edge/extract-closest-edge'
import { useQueryClient, useSuspenseQuery } from '@tanstack/react-query'
import { useEffect, useEffectEvent, useRef, useState } from 'react'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Lozenge } from '@/shared/ui/Lozenge.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { statusTone } from '@/shared/lib/enums.ts'
import { useSaveWorkflow } from './mutations.ts'
import { settingsKeys, workflowQuery } from './queries.ts'
import {
  ANY,
  addStatus,
  draftOf,
  move,
  pair,
  problemsOf,
  remap,
  removeStatus,
  requestOf,
  toggleTransition,
  updateStatus,
  type Category,
  type DraftStatus,
  type WorkflowDraft,
} from './workflow-draft.ts'
import styles from './Settings.module.css'

const categories: { value: Category; label: string }[] = [
  { value: 'todo', label: 'To do' },
  { value: 'in_progress', label: 'In progress' },
  { value: 'done', label: 'Done' },
]
const colors = ['todo', 'progress', 'review', 'done', 'blocked'] as const

/**
 * The workflow editor (phase 17): statuses (drag to reorder), their category, colour and WIP limit, and the
 * transitions as a from × to matrix of checkboxes. Nothing is sent until Save; then the whole workflow goes in one
 * PUT with If-Match. Keyed by the ETag: when the server's workflow changes (a reload, someone's save), the draft
 * starts over from it.
 */
export function WorkflowEditor({ projectKey, canEdit }: { projectKey: string; canEdit: boolean }) {
  const { data } = useSuspenseQuery(workflowQuery(projectKey))
  return (
    <Editor
      key={data.etag}
      projectKey={projectKey}
      canEdit={canEdit}
      etag={data.etag}
      initial={draftOf(data.workflow)}
    />
  )
}

function Editor({
  projectKey,
  canEdit,
  etag,
  initial,
}: {
  projectKey: string
  canEdit: boolean
  etag: string
  initial: WorkflowDraft
}) {
  const queryClient = useQueryClient()
  const save = useSaveWorkflow(projectKey)
  const [draft, setDraft] = useState(initial)
  const [dirty, setDirty] = useState(false)
  const edit = (next: WorkflowDraft) => {
    setDraft(next)
    setDirty(true)
  }
  const problems = problemsOf(draft)
  const stale = isApiError(save.error) && save.error.status === 412
  const kept = draft.statuses

  return (
    <form
      className={styles.workflow}
      onSubmit={(event) => {
        event.preventDefault()
        if (problems.length === 0) {
          save.mutate({ body: requestOf(draft), etag })
        }
      }}
    >
      <fieldset disabled={!canEdit} className={styles.fieldset}>
        <legend className="visually-hidden">Workflow</legend>

        {/* BLOCK 1: statuses — one row each, in workflow order */}
        <section aria-labelledby="statuses-title" className={styles.block}>
          <div className={styles.blockHead}>
            <h2 id="statuses-title" className={styles.blockTitle}>
              Statuses
            </h2>
            <Button
              size="sm"
              icon="plus"
              onClick={() => {
                edit(addStatus(draft, crypto.randomUUID(), `Status ${String(draft.statuses.length + 1)}`))
              }}
            >
              Add status
            </Button>
          </div>
          <ol className={styles.statusList}>
            {draft.statuses.map((status, index) => (
              <StatusRow
                key={status.id}
                status={status}
                index={index}
                count={draft.statuses.length}
                canEdit={canEdit}
                onChange={(change) => {
                  edit(updateStatus(draft, status.id, change))
                }}
                onMove={(from, to) => {
                  edit(move(draft, from, to))
                }}
                onRemove={() => {
                  edit(removeStatus(draft, status.id))
                }}
              />
            ))}
          </ol>
        </section>

        {/* BLOCK 2: removed statuses that had been saved — their issues need a new status */}
        {Object.keys(draft.removed).length > 0 && (
          <section aria-labelledby="removed-title" className={styles.block}>
            <h2 id="removed-title" className={styles.blockTitle}>
              Removed statuses
            </h2>
            <p className={styles.muted}>
              Issues in a removed status move to the status you choose when you save.
            </p>
            {Object.entries(draft.removed).map(([id, to]) => (
              <label key={id} className={styles.remap}>
                <span>
                  Issues in <strong>{draft.removedNames[id]}</strong> move to
                </span>
                <Select
                  value={to}
                  required
                  onChange={(event) => {
                    edit(remap(draft, id, event.target.value))
                  }}
                >
                  <option value="">Choose a status…</option>
                  {kept.map((status) => (
                    <option key={status.id} value={status.id}>
                      {status.name}
                    </option>
                  ))}
                </Select>
              </label>
            ))}
          </section>
        )}

        {/* BLOCK 3: transitions — rows are "from", columns are "to"; the first row is "from any status" */}
        <section aria-labelledby="transitions-title" className={styles.block}>
          <h2 id="transitions-title" className={styles.blockTitle}>
            Transitions
          </h2>
          <div className={styles.matrixScroller}>
            <table className={styles.matrix}>
              <caption className="visually-hidden">
                Allowed moves: from the row's status to the column's
              </caption>
              <thead>
                <tr>
                  <th scope="col">From ↓ / To →</th>
                  {kept.map((status) => (
                    <th key={status.id} scope="col">
                      {status.name || '…'}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {[{ id: ANY, name: 'Any status' }, ...kept].map((from) => (
                  <tr key={from.id}>
                    <th scope="row">{from.name || '…'}</th>
                    {kept.map((to) => (
                      <td key={to.id}>
                        {from.id === to.id ? (
                          <span aria-hidden="true">—</span>
                        ) : (
                          <input
                            type="checkbox"
                            aria-label={`From ${from.name} to ${to.name}`}
                            checked={draft.transitions.has(pair(from.id === ANY ? null : from.id, to.id))}
                            onChange={(event) => {
                              edit(
                                toggleTransition(
                                  draft,
                                  from.id === ANY ? null : from.id,
                                  to.id,
                                  event.target.checked,
                                ),
                              )
                            }}
                          />
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </fieldset>

      {/* BLOCK 4: save — problems first, the server's answer after; 412 offers the reload */}
      {stale ? (
        <div role="alert" className={styles.alert}>
          <p>
            Someone changed the workflow while you were editing it. Reload to see their version, then make
            your change again.
          </p>
          <Button
            variant="primary"
            onClick={() => {
              save.reset()
              void queryClient.invalidateQueries({ queryKey: settingsKeys.workflow(projectKey) })
            }}
          >
            Reload
          </Button>
        </div>
      ) : (
        save.error && (
          <div role="alert" className={styles.alert}>
            <p>{messageOf(save.error)}</p>
            {isApiError(save.error) && save.error.problem.errors && (
              <ul>
                {save.error.problem.errors.map((error) => (
                  <li key={`${error.field}-${error.message}`}>{error.message}</li>
                ))}
              </ul>
            )}
          </div>
        )
      )}
      {dirty && problems.length > 0 && (
        <ul className={styles.problems} aria-live="polite">
          {problems.map((problem) => (
            <li key={problem}>{problem}</li>
          ))}
        </ul>
      )}
      {canEdit && (
        <div className={styles.saveBar}>
          <Button
            disabled={!dirty || save.isPending}
            onClick={() => {
              setDraft(initial)
              setDirty(false)
              save.reset()
            }}
          >
            Discard changes
          </Button>
          <Button type="submit" variant="primary" disabled={!dirty || problems.length > 0 || save.isPending}>
            {save.isPending ? 'Saving…' : 'Save workflow'}
          </Button>
        </div>
      )}
    </form>
  )
}

/**
 * One status. Draggable by its handle (Pragmatic drag and drop, like the board), and a drop target that knows
 * which half the pointer is over (closest edge) — that decides "before" or "after". ↑/↓ buttons do the same for
 * keyboard users.
 */
function StatusRow({
  status,
  index,
  count,
  canEdit,
  onChange,
  onMove,
  onRemove,
}: {
  status: DraftStatus
  index: number
  count: number
  canEdit: boolean
  onChange: (change: Partial<Omit<DraftStatus, 'id' | 'saved'>>) => void
  onMove: (from: number, to: number) => void
  onRemove: () => void
}) {
  const row = useRef<HTMLLIElement>(null)
  const handle = useRef<HTMLButtonElement>(null)
  const [edge, setEdge] = useState<'top' | 'bottom' | null>(null)
  const [dragging, setDragging] = useState(false)
  const moveTo = useEffectEvent(onMove)
  const myIndex = useEffectEvent(() => index)

  useEffect(() => {
    const element = row.current
    const dragHandle = handle.current
    if (!element || !dragHandle || !canEdit) {
      return
    }
    return combine(
      draggable({
        element,
        dragHandle,
        getInitialData: () => ({ type: 'status', index: myIndex() }),
        onDragStart: () => {
          setDragging(true)
        },
        onDrop: () => {
          setDragging(false)
        },
      }),
      dropTargetForElements({
        element,
        canDrop: ({ source }) => source.data.type === 'status',
        getData: ({ input }) =>
          attachClosestEdge({ index: myIndex() }, { input, element, allowedEdges: ['top', 'bottom'] }),
        onDrag: ({ self }) => {
          const closest = extractClosestEdge(self.data)
          setEdge(closest === 'top' || closest === 'bottom' ? closest : null)
        },
        onDragLeave: () => {
          setEdge(null)
        },
        onDrop: ({ source, self }) => {
          setEdge(null)
          const from = Number(source.data.index)
          const target = myIndex() + (extractClosestEdge(self.data) === 'bottom' ? 1 : 0)
          // removing the row first shifts everything after it up by one
          moveTo(from, from < target ? target - 1 : target)
        },
      }),
    )
  }, [canEdit])

  return (
    <li
      ref={row}
      className={styles.statusRow}
      data-edge={edge ?? undefined}
      data-dragging={dragging || undefined}
    >
      <button
        ref={handle}
        type="button"
        className={styles.dragHandle}
        aria-label={`Drag ${status.name} to reorder`}
        tabIndex={-1}
      >
        <Icon name="drag" size="sm" />
      </button>
      <Lozenge tone={statusTone(status.color)} className={styles.preview}>
        {status.name || '…'}
      </Lozenge>
      <label className={styles.inline}>
        <span className="visually-hidden">Name of status {index + 1}</span>
        <input
          className={styles.textInput}
          value={status.name}
          maxLength={30}
          onChange={(event) => {
            onChange({ name: event.target.value })
          }}
        />
      </label>
      <label className={styles.inline}>
        <span className="visually-hidden">Category of {status.name}</span>
        <Select
          value={status.category}
          onChange={(event) => {
            onChange({ category: event.target.value as Category })
          }}
        >
          {categories.map((category) => (
            <option key={category.value} value={category.value}>
              {category.label}
            </option>
          ))}
        </Select>
      </label>
      <label className={styles.inline}>
        <span className="visually-hidden">Colour of {status.name}</span>
        <Select
          value={status.color}
          onChange={(event) => {
            onChange({ color: event.target.value })
          }}
        >
          {colors.map((color) => (
            <option key={color} value={color}>
              {color}
            </option>
          ))}
        </Select>
      </label>
      <label className={styles.wip}>
        <span>WIP</span>
        <input
          type="number"
          min={1}
          max={999}
          className={styles.numberInput}
          aria-label={`WIP limit of ${status.name}`}
          value={status.wipLimit ?? ''}
          placeholder="—"
          onChange={(event) => {
            onChange({ wipLimit: event.target.value === '' ? null : Number(event.target.value) })
          }}
        />
      </label>
      <span className={styles.rowActions}>
        <Button
          variant="ghost"
          size="sm"
          icon="chevron-up"
          aria-label={`Move ${status.name} up`}
          disabled={index === 0}
          onClick={() => {
            onMove(index, index - 1)
          }}
        />
        <Button
          variant="ghost"
          size="sm"
          icon="chevron-down"
          aria-label={`Move ${status.name} down`}
          disabled={index === count - 1}
          onClick={() => {
            onMove(index, index + 1)
          }}
        />
        <Button
          variant="ghost"
          size="sm"
          icon="trash"
          aria-label={`Remove ${status.name}`}
          onClick={onRemove}
        />
      </span>
    </li>
  )
}

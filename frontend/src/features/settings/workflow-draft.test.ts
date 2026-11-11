import { describe, expect, it } from 'vitest'
import type { WorkflowView } from '@/shared/api/types.ts'
import {
  addStatus,
  draftOf,
  move,
  problemsOf,
  remap,
  removeStatus,
  requestOf,
  toggleTransition,
  updateStatus,
} from './workflow-draft.ts'

const workflow: WorkflowView = {
  version: 3,
  statuses: [
    { id: 'todo', name: 'To do', category: 'todo', position: 0, color: 'todo' },
    { id: 'doing', name: 'Doing', category: 'in_progress', position: 1, color: 'progress', wipLimit: 3 },
    { id: 'done', name: 'Done', category: 'done', position: 2, color: 'done' },
  ],
  transitions: [
    { fromStatusId: 'todo', toStatusId: 'doing' },
    { fromStatusId: 'doing', toStatusId: 'done' },
    { fromStatusId: null, toStatusId: 'todo' },
  ],
}

describe('workflow draft', () => {
  it('round-trips the server workflow', () => {
    expect(requestOf(draftOf(workflow))).toEqual({
      statuses: [
        { id: 'todo', name: 'To do', category: 'todo', color: 'todo', wipLimit: null },
        { id: 'doing', name: 'Doing', category: 'in_progress', color: 'progress', wipLimit: 3 },
        { id: 'done', name: 'Done', category: 'done', color: 'done', wipLimit: null },
      ],
      transitions: [
        { fromStatusId: 'todo', toStatusId: 'doing' },
        { fromStatusId: 'doing', toStatusId: 'done' },
        { fromStatusId: null, toStatusId: 'todo' },
      ],
      remapTo: {},
    })
  })

  it('reorders statuses', () => {
    expect(move(draftOf(workflow), 2, 0).statuses.map((s) => s.id)).toEqual(['done', 'todo', 'doing'])
    expect(move(draftOf(workflow), 0, 9).statuses.map((s) => s.id)).toEqual(['todo', 'doing', 'done'])
  })

  it('gives a new status an any → it transition, and a category its colour', () => {
    let draft = addStatus(draftOf(workflow), 'qa', 'QA')
    draft = updateStatus(draft, 'qa', { category: 'in_progress' })
    const request = requestOf(draft)
    expect(request.statuses.at(-1)).toEqual({
      id: 'qa',
      name: 'QA',
      category: 'in_progress',
      color: 'progress',
      wipLimit: null,
    })
    expect(request.transitions).toContainEqual({ fromStatusId: null, toStatusId: 'qa' })
  })

  it('needs a target for the issues of a removed saved status, and drops its transitions', () => {
    let draft = removeStatus(draftOf(workflow), 'doing')
    expect(problemsOf(draft)).toEqual(['Choose where the issues of Doing go.'])

    draft = remap(draft, 'doing', 'done')
    expect(problemsOf(draft)).toEqual([])
    const request = requestOf(draft)
    expect(request.remapTo).toEqual({ doing: 'done' })
    expect(request.transitions).toEqual([{ fromStatusId: null, toStatusId: 'todo' }])
  })

  it('forgets a new status that is removed again', () => {
    const draft = removeStatus(addStatus(draftOf(workflow), 'qa', 'QA'), 'qa')
    expect(draft.removed).toEqual({})
    expect(draft.statuses).toHaveLength(3)
  })

  it('toggles transitions in the matrix', () => {
    const off = toggleTransition(draftOf(workflow), 'todo', 'doing', false)
    expect(requestOf(off).transitions).not.toContainEqual({ fromStatusId: 'todo', toStatusId: 'doing' })
    const on = toggleTransition(off, 'done', 'todo', true)
    expect(requestOf(on).transitions).toContainEqual({ fromStatusId: 'done', toStatusId: 'todo' })
  })

  it('reports blank and duplicate names, and a workflow with nothing to start in', () => {
    let draft = updateStatus(draftOf(workflow), 'doing', { name: 'done ' })
    draft = updateStatus(draft, 'todo', { name: ' ', category: 'in_progress' })
    expect(problemsOf(draft)).toEqual([
      'Every status needs a name.',
      'Two statuses have the same name.',
      'New issues start in a To do status: keep at least one.',
    ])
  })
})

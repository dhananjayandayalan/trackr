import { expect } from '@playwright/test'
import {
  ADA,
  GRACE,
  cardIn,
  column,
  dragToColumn,
  firstKey,
  moveWithMenu,
  openBoard,
  placeOf,
  test,
} from './helpers.ts'

// Phase 14's "Done when", against the running app and the dev seed (MAR: active sprint, columns To do,
// In progress, In review, Done; every move allowed). Each test puts the card it moved back where it was
// (restoreLater), even when it fails, so the dev board is the same after a run.

test('a move in one window appears in the other within a second', async ({ signIn, restoreLater }) => {
  const ada = await signIn(ADA)
  const grace = await signIn(GRACE)
  await openBoard(ada)
  await openBoard(grace)
  // presence: each sees the other
  await expect(ada.getByRole('list', { name: `Also here: ${GRACE.name}` })).toBeVisible()

  const key = await firstKey(column(ada, 'To do'))
  restoreLater(ada, await placeOf(ada, column(ada, 'To do'), key))
  const answered = ada.waitForResponse((r) => r.url().includes(`/issues/${key}/move`))
  await dragToColumn(ada, cardIn(column(ada, 'To do'), key), column(ada, 'In progress'))
  await expect(cardIn(column(ada, 'In progress'), key)).toBeVisible()

  // the other window: the card arrives in the same column within 1 s of the server's answer
  expect((await answered).ok()).toBe(true)
  const start = Date.now()
  await expect(cardIn(column(grace, 'In progress'), key)).toBeVisible({ timeout: 1000 })
  test.info().annotations.push({ type: 'live latency', description: `${Date.now() - start} ms` })
  await expect(cardIn(column(grace, 'To do'), key)).toHaveCount(0)

  // back the keyboard way (the card's menu), and that arrives on the other side too
  await moveWithMenu(ada, key, 'To do')
  await expect(cardIn(column(grace, 'To do'), key)).toBeVisible({ timeout: 2000 })
})

test('on a slow network the card lands at once; an injected 409 snaps it back with a toast', async ({
  signIn,
  restoreLater,
}) => {
  const ada = await signIn(ADA)
  await openBoard(ada)
  const todo = column(ada, 'To do')
  const review = column(ada, 'In review')
  const key = await firstKey(todo)
  // restoring goes through page.request (Node side): neither the throttling nor the route below touch it
  restoreLater(ada, await placeOf(ada, todo, key))

  // BLOCK 1: "Slow 3G": 2 s latency, ~50 kB/s
  const cdp = await ada.context().newCDPSession(ada)
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 2000,
    downloadThroughput: 50_000,
    uploadThroughput: 50_000,
  })
  const answered = ada.waitForResponse((r) => r.url().includes(`/issues/${key}/move`), { timeout: 20_000 })
  await dragToColumn(ada, cardIn(todo, key), review)
  const dropped = Date.now()
  // on screen long before the request could even reach the server
  await expect(cardIn(review, key)).toBeVisible({ timeout: 300 })
  test.info().annotations.push({ type: 'optimistic landing', description: `${Date.now() - dropped} ms` })
  expect((await answered).ok()).toBe(true)
  test
    .info()
    .annotations.push({ type: 'server answer (throttled)', description: `${Date.now() - dropped} ms` })
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 0,
    downloadThroughput: -1,
    uploadThroughput: -1,
  })

  // BLOCK 2: the server refuses the next move (409): optimistic first, then back, and a toast says why
  await ada.route('**/api/v1/issues/*/move', (route) =>
    route.fulfill({
      status: 409,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        status: 409,
        code: 'TRANSITION_NOT_ALLOWED',
        title: 'Conflict',
        detail: 'Not allowed.',
      }),
    }),
  )
  await dragToColumn(ada, cardIn(review, key), column(ada, 'Done'))
  await expect(ada.getByText("That move isn't allowed by the workflow.")).toBeVisible()
  await expect(cardIn(review, key)).toBeVisible()
  await expect(cardIn(column(ada, 'Done'), key)).toHaveCount(0)
})

test('the backlog stays smooth with 1 000+ issues: only what is in view is rendered', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.goto('/p/MAR/backlog')
  const backlog = ada.getByRole('region', { name: 'Backlog', exact: true })
  const toggle = backlog.getByRole('button', { expanded: true })
  await expect(toggle).toContainText(/\d+ issues/)
  const total = Number(/(\d+) issues/.exec((await toggle.textContent()) ?? '')?.[1])
  test.skip(total < 1000, 'seed the long backlog first: infra/postgres/seed-big-backlog.sql')

  const list = backlog.getByRole('list')
  expect(await list.getByRole('listitem').count()).toBeLessThan(60)

  // scroll the whole list, one step per frame, timing the frames: a long frame is a visible stutter
  const frames = await list.evaluate(async (element) => {
    const scroller = element.parentElement
    if (!scroller) return { frames: 0, p95: 0, max: 0 }
    const gaps: number[] = []
    let last = performance.now()
    while (scroller.scrollTop + scroller.clientHeight < scroller.scrollHeight - 1) {
      scroller.scrollTop += 400
      await new Promise((resolve) => requestAnimationFrame(resolve))
      const now = performance.now()
      gaps.push(now - last)
      last = now
    }
    gaps.sort((a, b) => a - b)
    return { frames: gaps.length, p95: gaps[Math.floor(gaps.length * 0.95)] ?? 0, max: gaps.at(-1) ?? 0 }
  })
  test.info().annotations.push({ type: 'scroll frames (ms)', description: JSON.stringify(frames) })
  expect(frames.frames).toBeGreaterThan(50)
  expect(frames.p95).toBeLessThan(50)

  // at the bottom: the last issue is drawn, and still only a window of rows is in the DOM
  await expect(list.getByRole('listitem').last()).toBeVisible()
  expect(await list.getByRole('listitem').count()).toBeLessThan(60)
})

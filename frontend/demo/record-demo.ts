// A scripted product tour of TRAKR, recorded as a video (phase 18). Playwright drives a real Chrome through every
// screen as the dev seed's Ada, with captions and a visible cursor drawn into the page. A second browser plays
// Grace, unrecorded, so the live board and the collaborative editor have someone to react to.
//
//   .\dev.ps1                      (repository root, in another window: api, collab, workers, Vite)
//   pnpm demo                      (from frontend/)  →  demo/out/trakr-demo-<time>.webm (+ .mp4 if ffmpeg is on PATH)
//   pnpm demo --headed             watch it while it records
//   pnpm demo --base=http://localhost   record the Caddy stack (infra/docker-compose.prod.yml) instead of Vite
//
// The tour changes a little data and puts it back at the end, even if a step fails: the two cards it moves go back
// to their places, and the issue it creates (with its description and comment) is deleted.
import { spawnSync } from 'node:child_process'
import { mkdirSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import {
  chromium,
  expect,
  type APIRequestContext,
  type BrowserContext,
  type Locator,
  type Page,
} from '@playwright/test'

// BLOCK 1: options
const args = new Map(
  process.argv.slice(2).map((arg) => {
    const [name, value] = arg.replace(/^--/, '').split('=')
    return [name ?? '', value ?? 'true'] as const
  }),
)
const BASE = args.get('base') ?? process.env.DEMO_BASE_URL ?? 'http://localhost:5173'
const HEADED = args.has('headed')
const OUT = join(import.meta.dirname, 'out')
const SIZE = { width: 1440, height: 900 }
const PASSWORD = 'correct-horse-7'
const ADA = { email: 'ada@trakr.dev', name: 'Ada Lovelace' }
const GRACE = { email: 'grace@trakr.dev', name: 'Grace Hopper' }

interface Card {
  key: string
  statusId: string
  version: number
}
interface Column {
  name: string
  statusIds: string[]
  cards: Card[]
}
interface Place {
  key: string
  statusId: string
  beforeKey: string | null
  afterKey: string | null
}

// BLOCK 2: the overlay — a caption bar and a cursor dot, installed into every page the recorded context opens.
// Playwright's video has no mouse pointer of its own. Styles are set through element.style (CSSOM), which a
// Content-Security-Policy without 'unsafe-inline' still allows, so this also works against the Caddy stack.
function installOverlay() {
  const ready = () => {
    if (document.getElementById('demo-caption')) return
    const caption = document.createElement('div')
    caption.id = 'demo-caption'
    Object.assign(caption.style, {
      position: 'fixed',
      left: '50%',
      bottom: '28px',
      transform: 'translateX(-50%)',
      zIndex: '2147483647',
      maxWidth: '80%',
      padding: '12px 22px',
      borderRadius: '12px',
      background: 'rgba(14, 14, 18, 0.88)',
      color: '#fff',
      font: '600 20px/1.35 Inter, system-ui, sans-serif',
      textAlign: 'center',
      boxShadow: '0 8px 30px rgba(0,0,0,.35)',
      pointerEvents: 'none',
      opacity: '0',
      transition: 'opacity .25s',
    })
    const cursor = document.createElement('div')
    cursor.id = 'demo-cursor'
    Object.assign(cursor.style, {
      position: 'fixed',
      left: '0',
      top: '0',
      width: '22px',
      height: '22px',
      marginLeft: '-11px',
      marginTop: '-11px',
      borderRadius: '50%',
      background: 'rgba(255, 210, 0, 0.55)',
      border: '2px solid rgba(14,14,18,.8)',
      zIndex: '2147483647',
      pointerEvents: 'none',
      transition: 'transform .08s',
    })
    document.body.append(caption, cursor)
    const saved = sessionStorage.getItem('demo-caption')
    if (saved) {
      caption.textContent = saved
      caption.style.opacity = '1'
    }
    document.addEventListener(
      'mousemove',
      (event) => {
        cursor.style.left = `${String(event.clientX)}px`
        cursor.style.top = `${String(event.clientY)}px`
      },
      true,
    )
    document.addEventListener('mousedown', () => (cursor.style.transform = 'scale(.7)'), true)
    document.addEventListener('mouseup', () => (cursor.style.transform = 'scale(1)'), true)
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready)
  else ready()
}

async function caption(page: Page, text: string, hold = 2200) {
  console.log(`  » ${text}`)
  await page.evaluate((value) => {
    // kept across full page loads (page.goto) so the caption doesn't flicker away
    sessionStorage.setItem('demo-caption', value)
    const element = document.getElementById('demo-caption')
    if (!element) return
    element.textContent = value
    element.style.opacity = value ? '1' : '0'
  }, text)
  await page.waitForTimeout(hold)
}

const pause = (page: Page, ms = 900) => page.waitForTimeout(ms)

// BLOCK 3: human-looking input — the cursor glides to an element before it clicks, text is typed key by key
async function glideTo(page: Page, target: Locator) {
  await target.scrollIntoViewIfNeeded()
  const box = await target.boundingBox()
  if (!box) throw new Error(`not on screen: ${target.toString()}`)
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 18 })
}

async function click(page: Page, target: Locator) {
  await glideTo(page, target)
  await pause(page, 250)
  await target.click()
}

async function type(target: Locator, text: string) {
  await target.pressSequentially(text, { delay: 55 })
}

/** Native drag and drop in small steps (Playwright's dragTo is too fast to watch, and Chrome needs the steps). */
async function drag(page: Page, from: Locator, to: Locator) {
  await from.scrollIntoViewIfNeeded()
  const a = await from.boundingBox()
  const b = await to.boundingBox()
  if (!a || !b) throw new Error('card or column not on screen')
  const y = Math.max(Math.min(b.y + b.height - 12, SIZE.height - 20), Math.max(b.y, 0) + 12)
  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2, { steps: 15 })
  await page.mouse.down()
  await page.mouse.move(a.x + a.width / 2 + 10, a.y + a.height / 2 + 5, { steps: 4 })
  await page.mouse.move(b.x + b.width / 2, y, { steps: 30 })
  await pause(page, 300)
  await page.mouse.up()
}

// BLOCK 4: the api, for Grace's moves, the setup and the clean-up — cookies of the context, CSRF echoed back
async function api<T>(
  request: APIRequestContext,
  method: string,
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<T> {
  const xsrf = (await request.storageState()).cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value
  const response = await request.fetch(`${BASE}${path}`, {
    method,
    ...(body === undefined ? {} : { data: body }),
    headers: { ...(xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf) } : {}), ...headers },
  })
  if (!response.ok())
    throw new Error(`${method} ${path}: ${String(response.status())} ${await response.text()}`)
  return (response.status() === 204 ? undefined : await response.json()) as T
}

async function signInByApi(context: BrowserContext, email: string) {
  await api(context.request, 'GET', '/api/v1/me').catch(() => undefined)
  await api(context.request, 'POST', '/api/v1/auth/login', { email, password: PASSWORD, remember: false })
}

async function columns(request: APIRequestContext): Promise<Column[]> {
  return (await api<{ columns: Column[] }>(request, 'GET', '/api/v1/projects/MAR/board')).columns
}

function placeIn(column: Column, index: number): Place {
  const card = column.cards[index]
  if (!card) throw new Error(`"${column.name}" has no card ${String(index)}`)
  return {
    key: card.key,
    statusId: card.statusId,
    beforeKey: column.cards[index - 1]?.key ?? null,
    afterKey: column.cards[index + 1]?.key ?? null,
  }
}

async function moveTo(request: APIRequestContext, key: string, place: Omit<Place, 'key'>) {
  const { version } = await api<{ version: number }>(request, 'GET', `/api/v1/issues/${key}`)
  await api(request, 'POST', `/api/v1/issues/${key}/move`, {
    toStatusId: place.statusId,
    beforeKey: place.beforeKey,
    afterKey: place.afterKey,
    expectedVersion: version,
  })
}

// BLOCK 5: the tour
async function main() {
  mkdirSync(OUT, { recursive: true })
  const videoDir = join(OUT, 'raw')
  rmSync(videoDir, { recursive: true, force: true })

  const browser = await chromium.launch({
    headless: !HEADED,
    ...(process.env.CI || process.env.DEMO_CHROMIUM ? {} : { channel: 'chrome' }),
  })
  // Ada's window is the one on video
  const adaContext = await browser.newContext({
    viewport: SIZE,
    recordVideo: { dir: videoDir, size: SIZE },
    baseURL: BASE,
  })
  await adaContext.addInitScript(installOverlay)
  // Grace's window plays the colleague and is never recorded
  const graceContext = await browser.newContext({ viewport: { width: 1200, height: 800 }, baseURL: BASE })
  const page = await adaContext.newPage()
  const undo: (() => Promise<void>)[] = []
  let failure: unknown

  try {
    await signInByApi(graceContext, GRACE.email)

    // 1. sign in
    await page.goto('/login')
    await caption(page, 'TRAKR — sprint boards, backlogs, search and live collaboration', 2600)
    await click(page, page.getByLabel('Email'))
    await type(page.getByLabel('Email'), ADA.email)
    await click(page, page.getByLabel('Password'))
    await type(page.getByLabel('Password'), PASSWORD)
    await click(page, page.getByRole('button', { name: 'Sign in' }))
    await expect(page).toHaveURL(`${BASE}/`)

    // 2. your work
    await caption(page, 'Your work: what is assigned to you, across every project', 2600)
    await page.mouse.wheel(0, 400)
    await pause(page, 1200)
    await page.mouse.wheel(0, -400)

    // 3. projects
    await click(page, page.getByRole('link', { name: 'Projects', exact: true }).first())
    await expect(page.getByRole('heading', { level: 1, name: 'Projects' })).toBeVisible()
    await caption(page, 'The projects directory: starred, recent and all projects')
    await page.goto('/projects/new')
    await caption(page, 'New projects start from a template: Scrum, Kanban or bug tracking', 2600)

    // 4. the sprint board — Ada drags a card, then Grace moves one from her own browser
    await page.goto('/p/MAR/board')
    await expect(page.getByRole('status').filter({ hasText: 'Live' })).toBeVisible()
    await caption(page, 'The sprint board. Drag a card to change its status')
    const before = await columns(graceContext.request)
    const todo = before.find((column) => column.name === 'To do')
    const review = before.find((column) => column.name === 'In review')
    if (!todo || !review) throw new Error('MAR board has no "To do" / "In review" column')
    const mine = placeIn(todo, 0)
    undo.push(() => moveTo(graceContext.request, mine.key, mine))
    const list = (name: string) => page.getByRole('list', { name, exact: true })
    const card = (name: string, key: string) =>
      list(name).getByRole('article', { name: new RegExp(`^${key}(,|$)`) })
    await drag(page, card('To do', mine.key), list('In progress'))
    await expect(card('In progress', mine.key)).toBeVisible()
    await caption(page, `${mine.key} is in progress — saved before the server even answered`)

    const hers = placeIn(review, 0)
    undo.push(() => moveTo(graceContext.request, hers.key, hers))
    await caption(page, 'Meanwhile Grace, in another browser, finishes a card…', 1500)
    const done = before.find((column) => column.name === 'Done')
    if (done) {
      await moveTo(graceContext.request, hers.key, {
        statusId: done.statusIds[0] ?? '',
        beforeKey: null,
        afterKey: null,
      })
      await expect(card('Done', hers.key)).toBeVisible({ timeout: 5000 })
    }
    await caption(
      page,
      `…and ${hers.key} moves to Done here within a second (Kafka → server-sent events)`,
      2800,
    )

    // 5. the side panel
    await click(page, card('In progress', mine.key))
    await caption(page, 'Click a card to open it beside the board', 2400)
    await page.keyboard.press('Escape')
    await pause(page)

    // 6. create an issue with the c shortcut
    await page.locator('main').click({ position: { x: 5, y: 5 } })
    await caption(page, 'Press c anywhere to create an issue', 1600)
    await page.keyboard.press('c')
    const dialog = page.getByRole('dialog', { name: 'Create issue' })
    await expect(dialog).toBeVisible()
    await type(dialog.getByLabel('Summary'), 'Record the product tour')
    await pause(page, 600)
    const created = page.waitForResponse(
      (response) => response.url().endsWith('/api/v1/issues') && response.request().method() === 'POST',
    )
    await click(page, dialog.getByRole('button', { name: 'Create', exact: true }))
    const issue = (await (await created).json()) as { key: string }
    undo.push(async () => {
      const { version } = await api<{ version: number }>(
        graceContext.request,
        'GET',
        `/api/v1/issues/${issue.key}`,
      )
      await api(graceContext.request, 'DELETE', `/api/v1/issues/${issue.key}`, undefined, {
        'If-Match': `"v${String(version)}"`,
      })
    })
    await caption(page, `${issue.key} created — it lands at the top of the backlog`)

    // 7. backlog
    await page.goto('/p/MAR/backlog')
    await expect(page.getByRole('heading', { level: 1, name: 'Backlog' })).toBeVisible()
    await caption(page, 'The backlog: plan sprints by dragging issues between lists', 2600)
    await page.mouse.wheel(0, 500)
    await pause(page, 1200)

    // 8. the issue page and collaborative editing
    const graceTab = await graceContext.newPage()
    await Promise.all([page.goto(`/p/MAR/issues/${issue.key}`), graceTab.goto(`/p/MAR/issues/${issue.key}`)])
    const editor = page.getByRole('textbox', { name: 'Description' })
    const graceEditor = graceTab.getByRole('textbox', { name: 'Description' })
    await expect(editor).toBeVisible()
    await expect(graceEditor).toBeVisible()
    await caption(page, 'The issue page. Descriptions are edited together, live', 2000)
    await click(page, editor)
    await type(editor, 'Show every screen in one take. ')
    await graceEditor.click()
    await graceTab.keyboard.press('Control+End')
    await graceTab.keyboard.press('Enter')
    await caption(page, 'Grace types at the same time — her caret and text appear as she writes', 800)
    await graceEditor.pressSequentially('Keep it under three minutes.', { delay: 70 })
    await pause(page, 1500)
    await expect(page.getByText('Saved', { exact: true })).toBeVisible({ timeout: 15_000 })
    await caption(page, 'Both edits merge (Yjs CRDT) and are saved — no locks, no conflicts', 2600)
    await graceTab.close()

    // 9. a comment
    const comment = page.getByRole('textbox', { name: `Comment on ${issue.key}` })
    await click(page, comment)
    await type(comment, 'Recording it now.')
    await page.keyboard.press('Control+Enter')
    await expect(page.getByRole('list', { name: 'Comments' }).getByText('Recording it now.')).toBeVisible()
    await caption(page, 'Comments, mentions, attachments, history and work logs live here too')

    // 10. search with TQL
    await page.goto('/issues?mode=query')
    const query = page.getByRole('combobox', { name: 'Query' })
    await caption(page, 'Search everything with TQL — a small query language with autocomplete', 1600)
    await click(page, query)
    await query.fill('')
    await type(query, 'project = MAR AND status != Done ORDER BY priority DESC')
    await query.press('Escape')
    await query.press('Enter')
    await expect(page.getByText(/\d+ issues? · \d+ ms/)).toBeVisible()
    await caption(page, 'Results come from Elasticsearch in milliseconds; save any query as a filter', 2600)

    // 11. the command palette
    await caption(page, 'Ctrl+K opens the command palette', 1200)
    await page.keyboard.press('Control+k')
    const palette = page.getByLabel('Search issues, places and actions')
    await expect(palette).toBeVisible()
    await type(palette, 'MAR timeline')
    await pause(page, 900)
    await page.keyboard.press('Enter')

    // 12. timeline
    await expect(page.getByRole('heading', { level: 1, name: 'Timeline' })).toBeVisible()
    await caption(page, 'The timeline: epics laid out over the weeks', 2600)

    // 13. reports
    await page.goto('/p/MAR/reports')
    await expect(page.getByRole('heading', { level: 1, name: 'Reports' })).toBeVisible()
    await caption(page, 'Reports: burndown, velocity, the sprint report and workload', 2200)
    await page.mouse.wheel(0, 600)
    await pause(page, 1500)

    // 14. settings
    await page.goto('/p/MAR/settings/workflow')
    await caption(page, 'Project settings: the workflow editor — statuses, transitions, WIP limits', 2600)
    await page.goto('/p/MAR/settings/people')
    await caption(page, 'People and roles: admin, member, viewer')

    // 15. notifications
    await click(page, page.getByRole('button', { name: /^Notifications/ }))
    await caption(page, 'Notifications arrive live: mentions, assignments, comments', 2400)
    await page.keyboard.press('Escape')

    await page.goto('/p/MAR/board')
    await caption(page, 'TRAKR — Spring Boot 4, Kafka, Elasticsearch, Yjs and React 19', 3200)
  } catch (error) {
    failure = error
    console.error('\nThe tour stopped early:', error)
    await caption(page, '').catch(() => undefined)
  } finally {
    // BLOCK 6: put the data back, newest change first, then save the video. Playwright finishes the file when the
    // recorded context closes, and saveAs needs the browser still running to copy it out.
    for (const step of undo.reverse()) {
      await step().catch((error: unknown) => {
        console.warn('clean-up step failed:', error)
      })
    }
    const video = page.video()
    await adaContext.close()
    if (video) {
      const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
      const file = join(OUT, `trakr-demo-${stamp}.webm`)
      await video.saveAs(file)
      console.log(`\nVideo: ${file}`)
      convertToMp4(file)
    }
    await graceContext.close()
    await browser.close()
    rmSync(videoDir, { recursive: true, force: true })
  }
  if (failure) process.exitCode = 1
}

// BLOCK 7: an .mp4 copy for players and sites that don't take WebM — only if ffmpeg is installed
function convertToMp4(webm: string) {
  if (spawnSync('ffmpeg', ['-version'], { stdio: 'ignore' }).status !== 0) {
    console.log('(no ffmpeg on PATH: kept the .webm only)')
    return
  }
  const mp4 = webm.replace(/\.webm$/, '.mp4')
  const result = spawnSync(
    'ffmpeg',
    [
      '-y',
      '-loglevel',
      'error',
      '-i',
      webm,
      '-c:v',
      'libx264',
      '-pix_fmt',
      'yuv420p',
      '-crf',
      '20',
      '-movflags',
      '+faststart',
      mp4,
    ],
    { stdio: 'inherit' },
  )
  if (result.status === 0) console.log(`Video: ${mp4}`)
}

await main()

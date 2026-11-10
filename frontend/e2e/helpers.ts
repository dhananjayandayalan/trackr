import {
  test as base,
  expect,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from '@playwright/test'

/** The dev seed's people (backend R__dev_seed.sql); both are members of MAR. */
export const ADA = { email: 'ada@trakr.dev', name: 'Ada Lovelace' }
export const GRACE = { email: 'grace@trakr.dev', name: 'Grace Hopper' }
const PASSWORD = 'correct-horse-7'

/**
 * The tests' `test`, with two fixtures:
 * - `signIn(who)`: a browser context of its own (its own cookies: a second "window", of another person), signed
 *   in. Every context it opened is closed when the test ends — left open, its stream would still count as
 *   "here" in the next test.
 * - `restoreLater(page, place)`: puts a card back where it was when the test ends — passed or failed or timed
 *   out. A `finally` in the test isn't enough: on a timeout Playwright tears the fixtures down (closing the
 *   pages) before the test's own code gets to run. This fixture depends on signIn, so it is torn down first,
 *   while the pages are still open.
 */
export const test = base.extend<{
  signIn: (who: { email: string }) => Promise<Page>
  restoreLater: (page: Page, place: CardPlace) => void
}>({
  signIn: async ({ browser }, use) => {
    const contexts: BrowserContext[] = []
    await use(async (who) => {
      const page = await signedIn(browser, who)
      contexts.push(page.context())
      return page
    })
    await Promise.all(contexts.map((context) => context.close()))
  },
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- named only to be torn down before signIn
  restoreLater: async ({ signIn: _signIn }, use) => {
    const places: [Page, CardPlace][] = []
    await use((page, place) => {
      places.push([page, place])
    })
    for (const [page, place] of places.reverse()) {
      await restoreCard(page, place)
    }
  },
})

async function signedIn(browser: Browser, who: { email: string }): Promise<Page> {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto('/login')
  await page.getByLabel('Email').fill(who.email)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL('/')
  return page
}

/** The board at /p/MAR/board, with its live stream connected. */
export async function openBoard(page: Page) {
  await page.goto('/p/MAR/board')
  await expect(page.getByRole('status').filter({ hasText: 'Live' })).toBeVisible()
}

/** A column's card list (one lane — the board is not grouped in these tests). */
export function column(page: Page, name: string): Locator {
  return page.getByRole('list', { name, exact: true })
}

/** A card by its key; its accessible name is the key (", flagged" may follow). */
export function cardIn(list: Locator, key: string): Locator {
  return list.getByRole('article', { name: new RegExp(`^${key}(,|$)`) })
}

/** The key of the first card of a column. */
export async function firstKey(list: Locator): Promise<string> {
  const label = await list.getByRole('article').first().getAttribute('aria-label')
  const key = label?.split(',')[0]
  if (!key) throw new Error('the column has no cards')
  return key
}

/**
 * Native drag and drop, as a user does it: press on the card, move in small steps, release over a point of the
 * target column that is on screen (its end, or the viewport's bottom edge if the column is taller than that).
 * Not Playwright's dragTo: to reach a point below the viewport it scrolls while the button is down, and
 * Chrome then never starts the drag.
 */
export async function dragToColumn(page: Page, card: Locator, target: Locator) {
  // the card must be on screen to be picked up (it may sit at the bottom of a long column)
  await card.scrollIntoViewIfNeeded()
  const from = await card.boundingBox()
  const to = await target.boundingBox()
  const viewport = page.viewportSize()
  if (!from || !to || !viewport) throw new Error('card or column not on screen')
  // the lowest point of the column that is inside the viewport
  const y = Math.max(Math.min(to.y + to.height - 8, viewport.height - 16), Math.max(to.y, 0) + 8)
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(to.x + to.width / 2, y, { steps: 12 })
  await page.mouse.up()
}

/** Where a card is: its status and its neighbours in its column — what restoreCard puts back. */
export interface CardPlace {
  key: string
  statusId: string
  beforeKey: string | null
  afterKey: string | null
}

export async function placeOf(page: Page, list: Locator, key: string): Promise<CardPlace> {
  const keys = await list
    .getByRole('article')
    .evaluateAll((cards) => cards.map((card) => card.getAttribute('aria-label')?.split(',')[0] ?? ''))
  const index = keys.indexOf(key)
  const issue = await apiCall<{ status: { id: string } }>(page, 'GET', `/api/v1/issues/${key}`)
  return {
    key,
    statusId: issue.status.id,
    beforeKey: keys[index - 1] ?? null,
    afterKey: keys[index + 1] ?? null,
  }
}

/** Put a card back where placeOf found it, through the api — runs even when the test failed half-way. */
export async function restoreCard(page: Page, place: CardPlace) {
  const { version } = await apiCall<{ version: number }>(page, 'GET', `/api/v1/issues/${place.key}`)
  await apiCall(page, 'POST', `/api/v1/issues/${place.key}/move`, {
    toStatusId: place.statusId,
    beforeKey: place.beforeKey,
    afterKey: place.afterKey,
    expectedVersion: version,
  })
}

// the page's session cookie, plus the CSRF header the api wants on a POST (the app's client does the same)
export async function apiCall<T = unknown>(
  page: Page,
  method: 'GET' | 'POST' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<T> {
  const cookies = await page.context().cookies()
  const xsrf = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value
  const response = await page.request.fetch(path, {
    method,
    ...(body === undefined ? {} : { data: body }),
    headers: { ...(xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf) } : {}), ...headers },
  })
  if (!response.ok()) throw new Error(`${method} ${path}: ${response.status()} ${await response.text()}`)
  return (response.status() === 204 ? undefined : await response.json()) as T
}

/**
 * The keyboard path, as a keyboard user takes it: focus the card's "Move …" button, Enter opens the menu (focus
 * goes to its first item), arrows down to the status, Enter moves the card. No pointer: the menu is anchored to
 * its button, and a pointer click that first scrolls the page would aim at where the menu *was*.
 */
export async function moveWithMenu(page: Page, key: string, status: string) {
  await page.getByRole('button', { name: `Move ${key}`, exact: true }).focus()
  await page.keyboard.press('Enter')
  const menu = page.getByRole('menu', { name: `Move ${key}` })
  await expect(menu).toBeVisible()
  const items = await menu.getByRole('menuitem').allTextContents()
  const index = items.indexOf(status)
  if (index < 0) throw new Error(`"${status}" is not in the menu: ${items.join(', ')}`)
  for (let i = 0; i < index; i++) {
    await page.keyboard.press('ArrowDown')
  }
  await expect(menu.getByRole('menuitem', { name: status, exact: true })).toBeFocused()
  await page.keyboard.press('Enter')
}

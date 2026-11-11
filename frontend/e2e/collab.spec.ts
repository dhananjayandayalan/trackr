import { expect, type Locator, type Page } from '@playwright/test'
import { ADA, GRACE, apiCall, test as base } from './helpers.ts'

interface Issue {
  key: string
  version: number
}

// Phase 16's "Done when", against the running stack (.\dev.ps1: api, collab, workers, Vite): two people type into
// the same description at the same time and end with the same text holding both edits, see each other's carets,
// and the text survives a reload and is found by search. The issue is made for the test and deleted after it.
const test = base.extend<{ freshIssue: Issue }>({
  freshIssue: async ({ signIn }, use) => {
    const ada = await signIn(ADA)
    const issue = await apiCall<Issue>(ada, 'POST', '/api/v1/issues', {
      projectKey: 'MAR',
      type: 'task',
      summary: 'Collaborative editing e2e',
      description: 'Written before collaborative editing.',
    })
    await use(issue)
    // teardown runs even when the test failed or timed out (a `finally` wouldn't on a timeout)
    const { version } = await apiCall<Issue>(ada, 'GET', `/api/v1/issues/${issue.key}`)
    await apiCall(ada, 'DELETE', `/api/v1/issues/${issue.key}`, undefined, { 'If-Match': `"v${version}"` })
  },
})

function editorOf(page: Page): Locator {
  return page.getByRole('textbox', { name: 'Description' })
}

// the document's text without the other people's caret labels (they sit inside the editable area)
async function documentText(editor: Locator): Promise<string> {
  return editor.evaluate((element) => {
    const copy = element.cloneNode(true) as HTMLElement
    copy.querySelectorAll('[class*="caret"]').forEach((caret) => {
      caret.remove()
    })
    return copy.textContent
  })
}

test('two people co-edit a description; it converges, persists and is searchable', async ({
  signIn,
  freshIssue,
}) => {
  test.setTimeout(60_000)
  const ada = await signIn(ADA)
  const grace = await signIn(GRACE)
  const url = `/p/MAR/issues/${freshIssue.key}`
  await Promise.all([ada.goto(url), grace.goto(url)])
  const adaEditor = editorOf(ada)
  const graceEditor = editorOf(grace)

  // BLOCK 1: the issue predates its shared document: exactly one of the two fills it from the stored description
  await expect(adaEditor).toContainText('Written before collaborative editing.')
  await expect(graceEditor).toContainText('Written before collaborative editing.')
  await expect.poll(() => documentText(adaEditor)).toBe('Written before collaborative editing.')

  // BLOCK 2: both type at the same time, keystroke by keystroke — Ada at the end, Grace at the start. (Typing at the
  // very same spot also converges, but the CRDT may then order the two runs either way round — e.g. Grace's leading
  // space after her words — so where one word ends and the next begins would differ from run to run.)
  const word = `quokka${Math.random().toString(36).slice(2, 8)}`
  await adaEditor.click()
  await graceEditor.click()
  await Promise.all([ada.keyboard.press('Control+End'), grace.keyboard.press('Control+Home')])
  await Promise.all([
    adaEditor.pressSequentially(` Ada adds ${word}.`, { delay: 40 }),
    graceEditor.pressSequentially('Grace adds hers. ', { delay: 40 }),
  ])

  // BLOCK 3: the CRDT converges — the same text on both screens, with both edits in it
  await expect
    .poll(async () => {
      const [a, g] = await Promise.all([documentText(adaEditor), documentText(graceEditor)])
      return a === g && a === `Grace adds hers. Written before collaborative editing. Ada adds ${word}.`
    })
    .toBe(true)

  // BLOCK 4: each sees the other's caret, labelled with their name
  await expect(
    grace.getByText(ADA.name, { exact: true }).and(grace.locator('[class*="caretLabel"]')),
  ).toBeVisible()
  await expect(
    ada.getByText(GRACE.name, { exact: true }).and(ada.locator('[class*="caretLabel"]')),
  ).toBeVisible()

  // BLOCK 5: a checkpoint stored it — the api's SSE event refetched the issue and the indicator says so
  await expect(ada.getByText('Saved', { exact: true })).toBeVisible({ timeout: 15_000 })
  const converged = await documentText(adaEditor)

  // BLOCK 6: a reload starts from what the server stored
  await ada.reload()
  await expect.poll(() => documentText(editorOf(ada))).toBe(converged)
  const issue = await apiCall<{ descriptionText: string }>(ada, 'GET', `/api/v1/issues/${freshIssue.key}`)
  expect(issue.descriptionText).toContain(word)

  // BLOCK 7: and search finds the word a few seconds later (api → Kafka → workers → Elasticsearch)
  await expect
    .poll(
      async () => {
        const page = await apiCall<{ items: { key: string }[] }>(ada, 'POST', '/api/v1/search', {
          tql: `text ~ "${word}"`,
        })
        return page.items.map((item) => item.key)
      },
      { timeout: 15_000 },
    )
    .toContain(freshIssue.key)
})

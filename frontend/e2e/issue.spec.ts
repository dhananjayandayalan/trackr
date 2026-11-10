import { expect, type Page } from '@playwright/test'
import { ADA, GRACE, apiCall, cardIn, column, firstKey, openBoard, placeOf, test } from './helpers.ts'

interface Issue {
  version: number
  assignee?: { id: string } | null
  storyPoints?: number | null
  attachments: { id: string }[]
}

// Phase 15's "Done when", against the running app and the dev seed: from the board, open a card in the side
// panel, change status, assignee and points inline, comment with a mention, upload a screenshot, close — and
// the board shows every change. Afterwards the issue is put back as it was (fields, comment, file, position).
test('the side panel edits an issue and the board behind it follows', async ({ signIn, restoreLater }) => {
  const ada = await signIn(ADA)
  await openBoard(ada)
  const key = await firstKey(column(ada, 'To do'))
  restoreLater(ada, await placeOf(ada, column(ada, 'To do'), key))
  const before = await apiCall<Issue>(ada, 'GET', `/api/v1/issues/${key}`)
  const added: { comment?: { id: string; version: number } } = {}
  test.info().annotations.push({ type: 'issue', description: key })

  try {
    // BLOCK 1: click the card → the panel slides in; the URL says which issue
    await cardIn(column(ada, 'To do'), key).getByRole('link').click()
    await expect(ada).toHaveURL(new RegExp(`[?&]issue=${key}`))
    const panel = ada.getByRole('complementary', { name: `Issue ${key}` })
    await expect(panel).toBeVisible()

    // BLOCK 2: status from the status menu — the card changes column behind the panel
    await panel.getByRole('button', { name: 'Status: To do. Change status' }).click()
    await ada
      .getByRole('menu', { name: `Move ${key} to` })
      .getByRole('menuitem', { name: 'In progress' })
      .click()
    await expect(panel.getByRole('button', { name: 'Status: In progress. Change status' })).toBeVisible()
    await expect(cardIn(column(ada, 'In progress'), key)).toBeVisible()

    // BLOCK 3: assignee and story points, each in its popover editor
    await panel.getByRole('button', { name: 'Assignee: edit' }).click()
    const assignee = ada.getByRole('form', { name: 'Edit Assignee' })
    await assignee.getByLabel('Assignee').selectOption({ label: GRACE.name })
    await assignee.getByRole('button', { name: 'Save' }).click()
    await expect(panel.getByRole('button', { name: 'Assignee: edit' })).toContainText(GRACE.name)

    await panel.getByRole('button', { name: 'Story points: edit' }).click()
    const points = ada.getByRole('form', { name: 'Edit Story points' })
    await points.getByLabel('Story points').fill('8')
    await points.getByRole('button', { name: 'Save' }).click()
    await expect(panel.getByRole('button', { name: 'Story points: edit' })).toHaveText('8')

    // BLOCK 4: a comment that mentions Grace, picked from the list that "@Gra" opens
    const composer = panel.getByRole('textbox', { name: `Comment on ${key}` })
    await composer.fill('Over to you ')
    await composer.pressSequentially('@Gra')
    await expect(ada.getByRole('option', { name: GRACE.name })).toBeVisible()
    await composer.press('Enter')
    await composer.pressSequentially('please review')
    const posted = ada.waitForResponse(
      (r) => r.url().endsWith(`/issues/${key}/comments`) && r.request().method() === 'POST',
    )
    await panel.getByRole('button', { name: 'Comment', exact: true }).click()
    added.comment = (await (await posted).json()) as { id: string; version: number }
    const comments = panel.getByRole('list', { name: 'Comments' })
    await expect(comments.locator('span[data-mention-id]').first()).toHaveText(`@${GRACE.name}`)

    // BLOCK 5: a screenshot as an attachment — presign, PUT straight to SeaweedFS, confirm; the thumbnail loads
    // through the api's redirect to a presigned GET
    const screenshot = await ada.screenshot()
    await panel.getByLabel('Choose files to attach').setInputFiles({
      name: 'board.png',
      mimeType: 'image/png',
      buffer: screenshot,
    })
    const thumbnail = panel.getByRole('link', { name: 'Open board.png' }).locator('img')
    await expect(thumbnail).toBeVisible({ timeout: 15_000 })
    await expect
      .poll(() => thumbnail.evaluate((img: HTMLImageElement) => img.naturalWidth))
      .toBeGreaterThan(0)
    await panel.screenshot({ path: test.info().outputPath('panel.png') })

    // BLOCK 6: close — the board shows every change
    await panel.getByRole('button', { name: 'Close' }).click()
    await expect(panel).toHaveCount(0)
    await expect(ada).not.toHaveURL(/issue=/)
    const card = cardIn(column(ada, 'In progress'), key)
    await expect(card.getByRole('img', { name: GRACE.name })).toBeVisible()
    await expect(card.getByTitle('Story points')).toHaveText('8')
  } finally {
    await putBack(ada, key, before, added.comment)
  }
})

test('c opens Create issue from any screen', async ({ signIn }) => {
  const ada = await signIn(ADA)
  for (const path of ['/', '/projects', '/p/MAR/backlog']) {
    await ada.goto(path)
    await ada.locator('main').click({ position: { x: 5, y: 5 } })
    await ada.keyboard.press('c')
    const dialog = ada.getByRole('dialog', { name: 'Create issue' })
    await expect(dialog).toBeVisible()
    // the c was a command, not a letter: the summary is empty and has focus
    await expect(dialog.getByLabel('Summary')).toBeFocused()
    await expect(dialog.getByLabel('Summary')).toHaveValue('')
    await ada.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
  }
})

// the issue's fields as they were, and nothing we added (the status/position is restoreLater's job)
async function putBack(page: Page, key: string, before: Issue, comment?: { id: string; version: number }) {
  if (comment) {
    await apiCall(page, 'DELETE', `/api/v1/comments/${comment.id}`, undefined, {
      'If-Match': `"v${String(comment.version)}"`,
    })
  }
  const now = await apiCall<Issue>(page, 'GET', `/api/v1/issues/${key}`)
  for (const attachment of now.attachments.filter((a) => !before.attachments.some((b) => b.id === a.id))) {
    await apiCall(page, 'DELETE', `/api/v1/attachments/${attachment.id}`)
  }
  await apiCall(
    page,
    'PATCH',
    `/api/v1/issues/${key}`,
    { assigneeId: before.assignee?.id ?? null, storyPoints: before.storyPoints ?? null },
    { 'If-Match': `"v${String(now.version)}"`, 'Content-Type': 'application/merge-patch+json' },
  )
}

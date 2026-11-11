import { expect } from '@playwright/test'
import { ADA, PASSWORD, expectAccessible, openBoard, test } from './helpers.ts'

// Phase 18: the 12 screens of 00-product §3, one test each — the screen shows its main content for the dev seed's
// Ada, and axe finds no WCAG 2.2 A/AA violation on it (00-product §8). Read-only: nothing here changes data, so the
// tests can run in any order next to the flow specs (board, issue, collab, search-reports).

test('3.1 sign in: the form signs Ada in and lands on Your work', async ({ browser }) => {
  // a context without the saved session: this is the one test that types a password
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto('/login')
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible()
  await expectAccessible(page)

  await page.getByLabel('Email').fill(ADA.email)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL('/')
  await context.close()
})

test('3.2 your work: greeting, stats and the work tabs', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await expect(ada.getByRole('heading', { level: 1, name: /Hi Ada/ })).toBeVisible()
  await expect(ada.getByRole('tablist').first()).toBeVisible()
  await expectAccessible(ada)
})

test('3.3 projects directory lists the seed projects', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.goto('/projects')
  await expect(ada.getByRole('heading', { level: 1, name: 'Projects' })).toBeVisible()
  await expect(ada.getByText('Marketing site').first()).toBeVisible()
  await expectAccessible(ada)
})

test('3.4 create project: the form with its templates', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.goto('/projects/new')
  await expect(ada.getByRole('heading', { level: 1, name: 'Create a project' })).toBeVisible()
  await expectAccessible(ada)
})

test('3.5 sprint board: columns, cards and the live stream', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await openBoard(ada)
  await expect(ada.getByRole('list', { name: 'To do', exact: true })).toBeVisible()
  await expectAccessible(ada)
})

test('3.6 backlog: the sprint and the backlog list', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.goto('/p/MAR/backlog')
  await expect(ada.getByRole('heading', { level: 1, name: 'Backlog' })).toBeVisible()
  await expectAccessible(ada)
})

test('3.7 issue detail: fields, description editor and activity', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.goto('/p/MAR/issues/MAR-1')
  await expect(ada.getByRole('textbox', { name: 'Description' })).toBeVisible()
  await expectAccessible(ada)
})

test('3.8 create issue: the dialog the c shortcut opens', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await openBoard(ada)
  await ada.keyboard.press('c')
  const dialog = ada.getByRole('dialog', { name: 'Create issue' })
  await expect(dialog).toBeVisible()
  await expectAccessible(ada)
  await ada.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
})

test('3.9 timeline: epics on a time axis', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.goto('/p/MAR/timeline')
  await expect(ada.getByRole('heading', { level: 1, name: 'Timeline' })).toBeVisible()
  await expectAccessible(ada)
})

test('3.10 reports: burndown, velocity and the sprint report', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.goto('/p/MAR/reports')
  await expect(ada.getByRole('heading', { level: 1, name: 'Reports' })).toBeVisible()
  await expect(ada.getByText(/Burndown/).first()).toBeVisible()
  await expectAccessible(ada)
})

test('3.11 issues and filters: a TQL search with results', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.goto('/issues')
  await expect(ada.getByRole('heading', { level: 1 })).toBeVisible()
  await expectAccessible(ada)
})

test('3.12 project settings: details, people and workflow', async ({ signIn }) => {
  const ada = await signIn(ADA)
  for (const section of ['details', 'people', 'workflow']) {
    await ada.goto(`/p/MAR/settings/${section}`)
    await expect(ada.getByRole('heading', { level: 1, name: 'Project settings' })).toBeVisible()
    await expect(ada.getByRole('tab', { selected: true })).toBeVisible()
    await expectAccessible(ada)
  }
})

test('3.13 global: the notifications popover and the command palette', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.getByRole('button', { name: /^Notifications/ }).click()
  await expect(ada.getByRole('dialog', { name: 'Notifications' })).toBeVisible()
  await expectAccessible(ada)
  await ada.keyboard.press('Escape')

  await ada.keyboard.press('Control+k')
  await expect(ada.getByRole('dialog', { name: 'Command palette' })).toBeVisible()
  await expectAccessible(ada)
})

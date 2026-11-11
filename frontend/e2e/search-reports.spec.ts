import { expect } from '@playwright/test'
import { ADA, apiCall, test } from './helpers.ts'

interface Filter {
  id: string
  name: string
  starred: boolean
}

// Phase 17's Playwright checks, against the running app and the dev seed (search needs the workers' index).

test('a TQL query runs, is saved as a filter and starred', async ({ signIn }) => {
  const ada = await signIn(ADA)
  const name = `E2E bugs ${String(Date.now())}`
  const created: { id?: string } = {}

  try {
    // BLOCK 1: query mode — type with the autocomplete open, run with Enter
    await ada.goto('/issues?mode=query')
    const editor = ada.getByRole('combobox', { name: 'Query' })
    await editor.fill('project = MAR AND type = bug ORDER BY key ASC')
    await editor.press('Escape')
    await editor.press('Enter')
    await expect(ada).toHaveURL(/mode=query/)
    await expect(ada.getByText(/\d+ issues? · \d+ ms/)).toBeVisible()
    const firstKey = ada.getByRole('table').getByRole('link').first()
    await expect(firstKey).toHaveText(/^MAR-\d+$/)

    // BLOCK 2: save it as a filter — it shows in the nav and is selected
    await ada.getByRole('button', { name: 'Save filter' }).click()
    const dialog = ada.getByRole('dialog', { name: 'Save filter' })
    await dialog.getByLabel('Name').fill(name)
    await dialog.getByLabel(/Star it/).uncheck()
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(ada.getByRole('heading', { level: 1, name })).toBeVisible()
    const id = new URL(ada.url()).searchParams.get('filter')
    if (id) {
      created.id = id
    }

    // BLOCK 3: star it — the star button flips, and the filter moves to "Starred"
    const nav = ada.getByRole('navigation', { name: 'Filters' })
    await nav.getByRole('button', { name: `Star ${name}` }).click()
    await expect(nav.getByRole('button', { name: `Unstar ${name}` })).toHaveAttribute('aria-pressed', 'true')
    await expect
      .poll(
        async () =>
          (await apiCall<Filter[]>(ada, 'GET', '/api/v1/filters')).find((f) => f.name === name)?.starred,
      )
      .toBe(true)

    // a syntax error is underlined, not a crash
    await editor.fill('project = AND')
    await editor.press('Escape')
    await editor.press('Enter')
    await expect(ada.getByRole('alert')).toBeVisible()
    await expect(editor).toHaveAttribute('aria-invalid', 'true')
  } finally {
    if (created.id) {
      await apiCall(ada, 'DELETE', `/api/v1/filters/${created.id}`)
    }
  }
})

test('the burndown renders the seeded sprint', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.goto('/p/MAR/reports')

  // the chart's accessible summary names the sprint and the commitment (79 points, R__dev_seed_reports.sql)
  const chart = ada.getByRole('img', { name: /Burndown of MAR Sprint 2: .* of 79 pts left/ })
  await expect(chart).toBeVisible()
  await ada.getByText('Show data table').first().click()
  const table = ada.getByRole('table', { name: 'Burndown of MAR Sprint 2' })
  // the day's label follows the browser's locale: "2 Nov" or "Nov 2"
  await expect(table.getByRole('row', { name: /^(2 Nov|Nov 2) 79 79$/ })).toBeVisible()
  await expect(table.getByRole('row', { name: /^(16 Nov|Nov 16) — 0$/ })).toBeVisible()

  // velocity has the closed sprint
  await expect(ada.getByRole('img', { name: /Velocity over 1 sprint/ })).toBeVisible()
})

test('keyboard: g b goes to the board, Ctrl+K opens the palette', async ({ signIn }) => {
  const ada = await signIn(ADA)
  await ada.goto('/p/MAR/reports')
  await expect(ada.getByRole('heading', { name: 'Reports' })).toBeVisible()

  await ada.keyboard.press('g')
  await ada.keyboard.press('b')
  await expect(ada).toHaveURL(/\/p\/MAR\/board/)

  await ada.keyboard.press('Control+k')
  const palette = ada.getByRole('dialog', { name: 'Command palette' })
  await expect(palette).toBeVisible()
  await palette.getByRole('combobox').fill('MAR-24')
  await ada.keyboard.press('Enter')
  await expect(ada).toHaveURL(/\/p\/MAR\/issues\/MAR-24/)
})

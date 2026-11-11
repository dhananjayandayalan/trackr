import { expect } from '@playwright/test'
import { ADA, test } from './helpers.ts'

// 00-product §8 budgets, measured in a real browser on a cold cache. Only meaningful against the production build
// (`vite preview`, what CI serves): the dev server ships unbundled, untransformed modules. Locally:
//   pnpm build; pnpm preview   (with the apps running), then   $env:E2E_PERF=1; pnpm e2e perf
test.skip(
  !process.env.CI && !process.env.E2E_PERF,
  'needs the production build (CI, or E2E_PERF=1 with vite preview)',
)

test('the board paints its largest element within 1.5 s; first-load JS is under 180 KB gzip', async ({
  signIn,
}) => {
  const ada = await signIn(ADA)
  // a fresh page in the same signed-in context, with the HTTP cache bypassed: a cold load
  const page = await ada.context().newPage()
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: true })

  await page.goto('/p/MAR/board')
  await expect(page.getByRole('list', { name: 'To do', exact: true })).toBeVisible()

  const lcp = await page.evaluate(
    () =>
      new Promise<number>((resolve) => {
        new PerformanceObserver((list) => {
          const entries = list.getEntries()
          resolve(entries[entries.length - 1]?.startTime ?? Number.NaN)
        }).observe({ type: 'largest-contentful-paint', buffered: true })
      }),
  )
  // scripts gzipped in the page with CompressionStream: the same number whether or not the server compresses
  // (vite preview doesn't; Caddy does, with zstd for browsers that take it). "First load" is what index.html
  // asks for — the entry script and its modulepreloads, as `pnpm size` counts it; the board's own route chunks
  // follow and are reported, not budgeted.
  const { firstLoad, total } = await page.evaluate(async () => {
    const gzipped = async (url: string) => {
      const body = (await fetch(url)).body
      return body
        ? (await new Response(body.pipeThrough(new CompressionStream('gzip'))).arrayBuffer()).byteLength
        : 0
    }
    // from index.html as served, not the live document: Vite adds modulepreload links for lazy chunks at runtime
    const html = new DOMParser().parseFromString(await (await fetch('/')).text(), 'text/html')
    const critical = new Set(
      [
        ...html.querySelectorAll<HTMLScriptElement | HTMLLinkElement>(
          'script[type=module][src], link[rel=modulepreload]',
        ),
      ].map((element) => element.getAttribute(element instanceof HTMLScriptElement ? 'src' : 'href') ?? ''),
    )
    const urls = [
      ...new Set(
        performance
          .getEntriesByType('resource')
          .map((entry) => entry.name)
          .filter((url) => new URL(url).pathname.endsWith('.js')),
      ),
    ]
    let first = 0
    let all = 0
    for (const url of urls) {
      const size = await gzipped(url)
      all += size
      if (critical.has(new URL(url).pathname)) first += size
    }
    return { firstLoad: first, total: all }
  })
  test.info().annotations.push({ type: 'LCP', description: `${lcp.toFixed(0)} ms` })
  test
    .info()
    .annotations.push({ type: 'first-load JS (gzip)', description: `${(firstLoad / 1024).toFixed(1)} KB` })
  test.info().annotations.push({
    type: 'all JS the board loaded (gzip)',
    description: `${(total / 1024).toFixed(1)} KB`,
  })
  expect(lcp).toBeLessThan(1500)
  expect(firstLoad).toBeGreaterThan(0)
  expect(firstLoad / 1024).toBeLessThan(180)
})

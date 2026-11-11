// Budget check for 00-product §8: "JS shipped on first load < 180 KB gzip". Run after `pnpm build`:
//   pnpm size
// First load = what index.html makes the browser fetch before any route code: the entry <script type="module"> and
// every <link rel="modulepreload">. Lazy chunks (the editor, each screen) come later and don't count.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { gzipSync } from 'node:zlib'

const BUDGET_KB = 180
const dist = new URL('../dist/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')

const html = readFileSync(join(dist, 'index.html'), 'utf8')
const files = [
  ...html.matchAll(/<script[^>]+type="module"[^>]+src="([^"]+)"/g),
  ...html.matchAll(/<link[^>]+rel="modulepreload"[^>]+href="([^"]+)"/g),
].map((match) => match[1])

let total = 0
for (const file of new Set(files)) {
  const bytes = gzipSync(readFileSync(join(dist, file))).length
  total += bytes
  console.log(`${(bytes / 1024).toFixed(1).padStart(7)} KB  ${file}`)
}
const totalKb = total / 1024
console.log(`${totalKb.toFixed(1).padStart(7)} KB  first-load JS, gzip (budget ${String(BUDGET_KB)} KB)`)
if (totalKb > BUDGET_KB) {
  console.error(`Over budget by ${(totalKb - BUDGET_KB).toFixed(1)} KB`)
  process.exit(1)
}

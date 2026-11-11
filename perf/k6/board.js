// Load test (phase 18, optional): 50 virtual users open MAR's board and move cards, checked against the p95
// targets of 00-product §8. Needs k6 (https://k6.io) and the running app; the dev auth limit (60 sign-ins a
// minute per IP) is enough for 50 users signing in once each.
//   k6 run perf/k6/board.js                      (against http://localhost:5173)
//   k6 run -e BASE=http://localhost perf/k6/board.js   (against the Caddy stack)
// Moves really happen: run it on the dev database, not on data you care about.
import http from 'k6/http'
import { check, sleep } from 'k6'

const BASE = __ENV.BASE || 'http://localhost:5173'
const USERS = ['ada@trakr.dev', 'grace@trakr.dev']

// BLOCK 1: the load shape and the budgets — the run fails if a threshold is crossed
export const options = {
  scenarios: {
    board: { executor: 'ramping-vus', stages: [
      { duration: '20s', target: 50 },
      { duration: '1m', target: 50 },
      { duration: '10s', target: 0 },
    ] },
  },
  thresholds: {
    'http_req_duration{name:board}': ['p(95)<150'],
    'http_req_duration{name:move}': ['p(95)<80'],
    http_req_failed: ['rate<0.01'],
  },
}

// BLOCK 2: each virtual user signs in once (its own cookie jar), then loops
function signIn() {
  const jar = http.cookieJar()
  http.get(`${BASE}/api/v1/me`)
  const xsrf = jar.cookiesForURL(BASE)['XSRF-TOKEN'][0]
  const email = USERS[__VU % USERS.length]
  http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ email, password: 'correct-horse-7', remember: false }), {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(xsrf) },
  })
  return () => decodeURIComponent(jar.cookiesForURL(BASE)['XSRF-TOKEN'][0])
}

let token

export default function () {
  token = token || signIn()

  // BLOCK 3: load the board, the request every visit starts with
  const board = http.get(`${BASE}/api/v1/projects/MAR/board`, { tags: { name: 'board' } })
  check(board, { 'board 200': (r) => r.status === 200 })
  const columns = board.json('columns') || []
  if (columns.length < 2) return sleep(1)

  // BLOCK 4: move the first card of a random column one column to the right, as a drop would
  const from = Math.floor(Math.random() * (columns.length - 1))
  const card = (columns[from].cards || [])[0]
  if (card) {
    const to = columns[from + 1]
    const move = http.post(`${BASE}/api/v1/issues/${card.key}/move`,
      JSON.stringify({ toStatusId: to.statusIds[0], expectedVersion: card.version }),
      { headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token() }, tags: { name: 'move' } })
    // 409/412: another virtual user moved the same card first — that is the optimistic lock working, not an error
    check(move, { 'move ok or lost a race': (r) => [200, 409, 412].includes(r.status) })
  }
  sleep(1)
}

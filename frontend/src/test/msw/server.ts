import { setupServer } from 'msw/node'
import { handlers } from './handlers.ts'

/** One fake api for every test file; tests add or override handlers with server.use(…). */
export const server = setupServer(...handlers)

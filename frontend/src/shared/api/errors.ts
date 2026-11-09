import { z } from 'zod'

// BLOCK 1: the wire format — RFC 9457 problem details as the api sends them (03-api §2). Parsed with zod,
// never cast: a proxy error page or a half-written body becomes a generic error instead of a crash.
const problemSchema = z.looseObject({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number(),
  detail: z.string().optional(),
  code: z.string().optional(),
  errors: z.array(z.object({ field: z.string(), message: z.string() })).optional(),
  current: z.unknown().optional(),
  // TQL errors (phase 11)
  position: z.number().optional(),
  expected: z.array(z.string()).optional(),
})

export type Problem = z.infer<typeof problemSchema>

// BLOCK 2: the error every api call throws. Screens branch on `code`, never on message text.
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly problem: Problem

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${problem.status}`)
    this.name = 'ApiError'
    this.status = problem.status
    this.code = problem.code ?? `HTTP_${problem.status}`
    this.problem = problem
  }

  /** Field → message, from the problem's errors[] (VALIDATION_FAILED). */
  get fieldErrors(): Record<string, string> {
    const fields: Record<string, string> = {}
    for (const error of this.problem.errors ?? []) {
      fields[error.field] ??= error.message
    }
    return fields
  }

  /** The body of an error response as an ApiError; anything that isn't a problem gets one made up. */
  static async from(response: Response): Promise<ApiError> {
    const type = response.headers.get('content-type') ?? ''
    if (type.includes('json')) {
      const parsed = problemSchema.safeParse(
        await response
          .clone()
          .json()
          .catch(() => null),
      )
      if (parsed.success) {
        return new ApiError(parsed.data)
      }
    }
    return new ApiError({ status: response.status, title: response.statusText || 'Request failed' })
  }
}

export function isApiError(error: unknown, code?: string): error is ApiError {
  return error instanceof ApiError && (code === undefined || error.code === code)
}

/** What a form shows when something failed that isn't a single field's fault. */
export function messageOf(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  return 'Something went wrong. Check your connection and try again.'
}

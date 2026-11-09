import { queryOptions } from '@tanstack/react-query'
import { api, unwrap } from '@/shared/api/client.ts'

export const projectKeys = {
  all: ['projects'] as const,
  list: () => [...projectKeys.all, 'list'] as const,
  detail: (key: string) => [...projectKeys.all, 'detail', key] as const,
  keySuggestion: (name: string) => [...projectKeys.all, 'key-suggestion', name] as const,
}

// Every visible project in one request (a workspace has tens, not thousands): the directory filters and
// searches them in the browser, so typing in its search box costs no requests.
export function projectsQuery() {
  return queryOptions({
    queryKey: projectKeys.list(),
    queryFn: () => unwrap(api.GET('/api/v1/projects')),
  })
}

export function projectQuery(key: string) {
  return queryOptions({
    queryKey: projectKeys.detail(key),
    queryFn: () => unwrap(api.GET('/api/v1/projects/{key}', { params: { path: { key } } })),
  })
}

// The name is part of the key: each name typed is its own cached answer, and typing back to an earlier
// name answers from the cache. A suggestion for a given name never changes within a minute.
export function keySuggestionQuery(name: string) {
  return queryOptions({
    queryKey: projectKeys.keySuggestion(name),
    queryFn: () => unwrap(api.GET('/api/v1/projects/key-suggestion', { params: { query: { name } } })),
    staleTime: 60_000,
  })
}

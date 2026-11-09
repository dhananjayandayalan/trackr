import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, postOutsideSchema, resetUnauthenticated, unwrap } from '@/shared/api/client.ts'
import type { Me } from '@/shared/api/types.ts'
import { meKeys } from './queries.ts'

export interface Credentials {
  email: string
  password: string
  remember: boolean
}

export interface SignUp {
  name: string
  email: string
  password: string
}

// Signing in or up answers with Me: put it straight into the cache, so the app shell renders at once
// instead of asking /me again.
export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (credentials: Credentials) => unwrap(api.POST('/api/v1/auth/login', { body: credentials })),
    onSuccess: (me: Me) => {
      resetUnauthenticated()
      queryClient.setQueryData(meKeys.me, me)
    },
  })
}

export function useSignUp() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (account: SignUp) => unwrap(api.POST('/api/v1/auth/signup', { body: account })),
    onSuccess: (me: Me) => {
      resetUnauthenticated()
      queryClient.setQueryData(meKeys.me, me)
    },
  })
}

// Signing out drops every cached query: the next user of this browser must not see the last one's data.
export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => postOutsideSchema('/api/v1/auth/logout'),
    onSettled: () => {
      queryClient.clear()
    },
  })
}

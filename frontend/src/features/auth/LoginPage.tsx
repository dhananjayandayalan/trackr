import { getRouteApi, useRouter } from '@tanstack/react-router'
import { useActionState, type ReactNode } from 'react'
import { useFormStatus } from 'react-dom'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import { safeNext } from '@/shared/lib/urls.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Field } from '@/shared/ui/Field.tsx'
import { Segmented } from '@/shared/ui/Segmented.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { useLogin, useSignUp } from './mutations.ts'
import styles from './LoginPage.module.css'

const route = getRouteApi('/login')

type Mode = 'signin' | 'signup'

/** What an action hands back to the form: per-field messages, a form-level message, and what was typed. */
interface FormState {
  errors: Record<string, string>
  message?: string
  values: Record<string, string>
}

const initial: FormState = { errors: {}, values: {} }

/** Sign in / create account (00-product §3.1). The mode is a search param, so /login?mode=signup is a link. */
export function LoginPage() {
  // BLOCK 1: the tab — Segmented bound to ?mode=; replace: true so switching tabs isn't a history entry each
  const search = route.useSearch()
  const navigate = route.useNavigate()
  const mode: Mode = search.mode ?? 'signin'

  return (
    <div className={styles.root}>
      <section className={styles.brand} aria-hidden="true">
        <p className={styles.logo}>
          <span className={styles.mark}>T</span>TRAKR
        </p>
        <p className={styles.slogan}>
          Track work at <span className={styles.highlight}>full voltage.</span>
        </p>
        <p className={styles.tagline}>Boards, backlogs, sprints and live collaboration — keyboard first.</p>
      </section>

      <main className={styles.panel}>
        <div className={styles.card}>
          <h1>{mode === 'signin' ? 'Sign in' : 'Create your account'}</h1>
          <Segmented
            label="Sign in or create an account"
            value={mode}
            options={[
              { value: 'signin', label: 'Sign in' },
              { value: 'signup', label: 'Create account' },
            ]}
            onChange={(next) =>
              void navigate({ search: (previous) => ({ ...previous, mode: next }), replace: true })
            }
          />
          {mode === 'signin' ? <SignInForm next={search.next} /> : <SignUpForm next={search.next} />}

          {/* BLOCK 5: OAuth — plain links: the browser leaves the SPA, Spring Security runs the redirect dance
              with Google/GitHub and lands back on / with a session cookie. Nothing for JavaScript to do. */}
          <div className={styles.divider}>
            <span>or</span>
          </div>
          <div className={styles.oauth}>
            <a className={styles.oauthLink} href="/oauth2/authorization/google">
              Continue with Google
            </a>
            <a className={styles.oauthLink} href="/oauth2/authorization/github">
              Continue with GitHub
            </a>
          </div>
        </div>
      </main>
    </div>
  )
}

// BLOCK 2: the sign-in form as a React 19 action. useActionState runs `signIn` when the form submits, gives
// it the FormData, and keeps what it returns as `state`; `pending` is true while it runs.
function SignInForm({ next }: { next: string | undefined }) {
  const login = useLogin()
  const router = useRouter()

  async function signIn(_previous: FormState, form: FormData): Promise<FormState> {
    const email = text(form, 'email')
    try {
      await login.mutateAsync({
        email,
        password: text(form, 'password'),
        remember: form.get('remember') === 'on',
      })
      // not awaited: React 19 keeps every form pending until all in-flight actions settle, so an action
      // must end when its own work (the request) is done — the navigation carries on by itself
      void router.navigate({ href: safeNext(next) })
      return initial
    } catch (error) {
      // React resets an action's form after it returns; handing the email back keeps what was typed
      return {
        errors: isApiError(error) ? error.fieldErrors : {},
        message: messageOf(error),
        values: { email },
      }
    }
  }

  const [state, formAction] = useActionState(signIn, initial)

  return (
    // BLOCK 3: a real form: labels, autocomplete hints for password managers, the browser's own checks
    <form action={formAction} className={styles.form}>
      <FormMessage message={state.message} />
      <Field label="Email" error={state.errors.email}>
        {(control) => (
          <TextInput
            {...control}
            name="email"
            type="email"
            autoComplete="username"
            required
            defaultValue={state.values.email ?? ''}
          />
        )}
      </Field>
      <Field label="Password" error={state.errors.password}>
        {(control) => (
          <TextInput {...control} name="password" type="password" autoComplete="current-password" required />
        )}
      </Field>
      <label className={styles.check}>
        <input type="checkbox" name="remember" /> Keep me signed in for 30 days
      </label>
      <SubmitButton pendingLabel="Signing in…">Sign in</SubmitButton>
    </form>
  )
}

function SignUpForm({ next }: { next: string | undefined }) {
  const signUp = useSignUp()
  const router = useRouter()

  async function createAccount(_previous: FormState, form: FormData): Promise<FormState> {
    const values = { name: text(form, 'name').trim(), email: text(form, 'email') }
    const password = text(form, 'password')
    // the server's rules (09-security §1), checked here first for an instant answer; the server checks again
    const problems = passwordProblems(password)
    if (problems.length > 0) {
      return { errors: { password: `Password ${problems.join(', ')}.` }, values }
    }
    try {
      await signUp.mutateAsync({ ...values, password })
      void router.navigate({ href: safeNext(next) })
      return initial
    } catch (error) {
      if (isApiError(error, 'EMAIL_TAKEN')) {
        return { errors: { email: 'An account with this email already exists.' }, values }
      }
      return { errors: isApiError(error) ? error.fieldErrors : {}, message: messageOf(error), values }
    }
  }

  const [state, formAction] = useActionState(createAccount, initial)

  return (
    <form action={formAction} className={styles.form}>
      <FormMessage message={state.message} />
      <Field label="Name" error={state.errors.name}>
        {(control) => (
          <TextInput
            {...control}
            name="name"
            autoComplete="name"
            required
            defaultValue={state.values.name ?? ''}
          />
        )}
      </Field>
      <Field label="Email" error={state.errors.email}>
        {(control) => (
          <TextInput
            {...control}
            name="email"
            type="email"
            autoComplete="email"
            required
            defaultValue={state.values.email ?? ''}
          />
        )}
      </Field>
      <Field
        label="Password"
        hint="At least 10 characters, with a number and a symbol."
        error={state.errors.password}
      >
        {(control) => (
          <TextInput
            {...control}
            name="password"
            type="password"
            autoComplete="new-password"
            required
            minLength={10}
          />
        )}
      </Field>
      <SubmitButton pendingLabel="Creating account…">Create account</SubmitButton>
    </form>
  )
}

// BLOCK 4: the submit button reads its form's pending state with useFormStatus — no prop drilling, no state
function SubmitButton({ children, pendingLabel }: { children: ReactNode; pendingLabel: string }) {
  const { pending } = useFormStatus()
  return (
    <Button type="submit" variant="primary" size="lg" disabled={pending} className={styles.submit}>
      {pending ? pendingLabel : children}
    </Button>
  )
}

function FormMessage({ message }: { message: string | undefined }) {
  return message ? (
    <p role="alert" className={styles.alert}>
      {message}
    </p>
  ) : null
}

export function passwordProblems(password: string): string[] {
  const problems: string[] = []
  if (password.length < 10) {
    problems.push('must be at least 10 characters')
  }
  if (!/\d/.test(password)) {
    problems.push('must contain a number')
  }
  if (/^[\p{L}\p{N}]*$/u.test(password)) {
    problems.push('must contain a symbol')
  }
  return problems
}

function text(form: FormData, name: string): string {
  const value = form.get(name)
  return typeof value === 'string' ? value : ''
}

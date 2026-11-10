import { Link } from '@tanstack/react-router'
import type { IssueDetails, ProjectDetails } from '@/shared/api/types.ts'
import { issueType } from '@/shared/lib/enums.ts'
import { Button, buttonClass } from '@/shared/ui/Button.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { useToast } from '@/shared/ui/Toast.tsx'
import { TypeIcon } from '@/shared/ui/TypeIcon.tsx'
import { useWatchIssue } from './mutations.ts'
import styles from './IssueView.module.css'

/** Breadcrumb (project / parent / issue), then watch, copy link, and — in the side panel — open and close. */
export function IssueHeader({
  issue,
  project,
  onClose,
}: {
  issue: IssueDetails
  project: ProjectDetails
  /** set in the side panel: shows "open full page" and the close button */
  onClose?: (() => void) | undefined
}) {
  const watch = useWatchIssue(issue.key)
  const toast = useToast()
  const pageUrl = new URL(`/p/${issue.projectKey}/issues/${issue.key}`, globalThis.location.origin).href

  return (
    <header className={styles.header}>
      <nav aria-label="Breadcrumb" className={styles.breadcrumb}>
        <ol role="list">
          <li>
            <Link to="/p/$projectKey/board" params={{ projectKey: issue.projectKey }}>
              {project.name}
            </Link>
          </li>
          {issue.parent && (
            <li>
              <Link
                to="/p/$projectKey/issues/$issueKey"
                params={{ projectKey: issue.projectKey, issueKey: issue.parent.key }}
              >
                {issue.parent.key}
              </Link>
            </li>
          )}
          <li aria-current="page" className={styles.crumbKey}>
            <TypeIcon type={issueType(issue.type)} />
            {issue.key}
          </li>
        </ol>
      </nav>

      <div className={styles.headerActions}>
        <Button
          size="sm"
          variant="ghost"
          icon="eye"
          aria-pressed={issue.watching}
          title={issue.watching ? 'Stop watching' : 'Watch: get notified about changes'}
          onClick={() => watch.mutate(!issue.watching)}
        >
          {issue.watching ? 'Watching' : 'Watch'} · {issue.watcherCount}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          icon="link"
          aria-label="Copy link"
          onClick={() => {
            void navigator.clipboard.writeText(pageUrl).then(
              () => toast('Link copied.', 'success'),
              () => toast("Couldn't copy the link.", 'error'),
            )
          }}
        />
        {onClose && (
          <>
            <Link
              to="/p/$projectKey/issues/$issueKey"
              params={{ projectKey: issue.projectKey, issueKey: issue.key }}
              className={buttonClass}
              data-variant="ghost"
              data-size="sm"
              data-icon-only
              aria-label="Open full page"
              title="Open full page"
            >
              <Icon name="external" size="sm" />
            </Link>
            <Button size="sm" variant="ghost" icon="close" aria-label="Close" onClick={onClose} />
          </>
        )}
      </div>
    </header>
  )
}

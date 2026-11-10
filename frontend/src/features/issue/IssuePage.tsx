import { getRouteApi } from '@tanstack/react-router'
import { IssueView } from './IssueView.tsx'
import styles from './IssuePage.module.css'

const route = getRouteApi('/_authed/p/$projectKey/issues/$issueKey')

/** /p/$projectKey/issues/$issueKey: the same IssueView as the side panel, with the whole page to itself. */
export function IssuePage() {
  const { issueKey } = route.useParams()
  return (
    <div className={styles.page}>
      <IssueView issueKey={issueKey} />
    </div>
  )
}

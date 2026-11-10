import { useSuspenseQuery } from '@tanstack/react-query'
import { useRef, useState, type DragEvent } from 'react'
import { meQuery } from '@/features/auth/queries.ts'
import type { Attachment, IssueDetails } from '@/shared/api/types.ts'
import { timeAgo } from '@/shared/lib/dates.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { Progress } from '@/shared/ui/Progress.tsx'
import { useDeleteAttachment } from './mutations.ts'
import { useUpload } from './useUpload.ts'
import sections from './IssueSections.module.css'
import styles from './Attachments.module.css'

const PREVIEWABLE = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp'])

/**
 * Files on the issue: a thumbnail for images, name, size, who added it. Add with the button, or drop files
 * anywhere on the section. Each upload shows its own progress (useUpload) and joins the list when confirmed.
 */
export function Attachments({
  issue,
  canEdit,
  isAdmin,
}: {
  issue: IssueDetails
  canEdit: boolean
  isAdmin: boolean
}) {
  const { data: me } = useSuspenseQuery(meQuery())
  const input = useRef<HTMLInputElement>(null)
  const { items, upload, dismiss } = useUpload(issue.key)
  const remove = useDeleteAttachment(issue.key)
  const [dropping, setDropping] = useState(false)

  const onDrop = (event: DragEvent) => {
    event.preventDefault()
    setDropping(false)
    if (canEdit && event.dataTransfer.files.length > 0) {
      void upload(event.dataTransfer.files)
    }
  }

  return (
    <section
      className={`${sections.section} ${styles.dropzone}`}
      aria-labelledby={`${issue.key}-attachments`}
      data-dropping={dropping || undefined}
      onDragOver={(event) => {
        // only file drags: cards and text being dragged elsewhere are none of our business
        if (canEdit && event.dataTransfer.types.includes('Files')) {
          event.preventDefault()
          setDropping(true)
        }
      }}
      onDragLeave={() => setDropping(false)}
      onDrop={onDrop}
    >
      <header className={sections.sectionHeader}>
        <h3 id={`${issue.key}-attachments`} className={sections.sectionTitle}>
          Attachments{issue.attachments.length > 0 && ` (${String(issue.attachments.length)})`}
        </h3>
        {canEdit && (
          <>
            <Button size="sm" variant="ghost" icon="attach" onClick={() => input.current?.click()}>
              Attach
            </Button>
            <input
              ref={input}
              type="file"
              multiple
              hidden
              aria-label="Choose files to attach"
              onChange={(event) => {
                if (event.target.files) {
                  void upload(event.target.files)
                }
                // the same file can be picked again after a failure
                event.target.value = ''
              }}
            />
          </>
        )}
      </header>

      {issue.attachments.length === 0 && items.length === 0 && (
        <p className={sections.muted}>
          {canEdit ? 'No files yet. Drop files here or use Attach.' : 'No files.'}
        </p>
      )}

      {issue.attachments.length > 0 && (
        <ul role="list" className={styles.grid}>
          {issue.attachments.map((attachment) => (
            <AttachmentTile
              key={attachment.id}
              attachment={attachment}
              canDelete={canEdit && (attachment.uploader.id === me.id || isAdmin)}
              onDelete={() => remove.mutate(attachment)}
            />
          ))}
        </ul>
      )}

      {items.length > 0 && (
        <ul role="list" className={styles.uploads} aria-label="Uploads">
          {items.map((item) => (
            <li key={item.id} className={styles.upload} data-status={item.status}>
              <span className={styles.uploadName}>{item.fileName}</span>
              {item.status === 'uploading' ? (
                <Progress value={item.progress * 100} label={`Uploading ${item.fileName}`} />
              ) : (
                <span role="alert" className={styles.uploadError}>
                  {item.error}
                </span>
              )}
              {item.status === 'error' && (
                <Button
                  size="sm"
                  variant="ghost"
                  icon="close"
                  aria-label="Dismiss"
                  onClick={() => dismiss(item.id)}
                />
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function AttachmentTile({
  attachment,
  canDelete,
  onDelete,
}: {
  attachment: Attachment
  canDelete: boolean
  onDelete: () => void
}) {
  // the api checks access and redirects to a one-minute presigned URL; <img> and <a> follow redirects
  const href = `/api/v1/attachments/${attachment.id}/content`
  return (
    <li className={styles.tile}>
      <a
        href={href}
        target="_blank"
        rel="noopener"
        className={styles.preview}
        aria-label={`Open ${attachment.fileName}`}
      >
        {PREVIEWABLE.has(attachment.contentType) ? (
          <img src={href} alt="" loading="lazy" className={styles.image} />
        ) : (
          <Icon name="attach" size="md" />
        )}
      </a>
      <div className={styles.meta}>
        <a href={href} target="_blank" rel="noopener" className={styles.name} title={attachment.fileName}>
          {attachment.fileName}
        </a>
        <span className={styles.details}>
          {formatBytes(attachment.sizeBytes)} · {attachment.uploader.displayName} ·{' '}
          {timeAgo(attachment.createdAt)}
        </span>
      </div>
      {canDelete && (
        <Button
          size="sm"
          variant="ghost"
          icon="trash"
          aria-label={`Remove ${attachment.fileName}`}
          onClick={onDelete}
        />
      )}
    </li>
  )
}

const units = ['B', 'KB', 'MB'] as const

export function formatBytes(bytes: number): string {
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${unit === 0 ? String(value) : value.toFixed(value < 10 ? 1 : 0)} ${units[unit] ?? 'B'}`
}

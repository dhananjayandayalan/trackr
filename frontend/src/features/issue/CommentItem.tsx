import { useState } from 'react'
import type { Comment } from '@/shared/api/types.ts'
import { timeAgo } from '@/shared/lib/dates.ts'
import { Avatar } from '@/shared/ui/Avatar.tsx'
import { avatarColor } from '@/shared/ui/AvatarGroup.tsx'
import { Button } from '@/shared/ui/Button.tsx'
import { SafeHtml } from '@/shared/ui/SafeHtml.tsx'
import { fromCommentHtml } from './comment-html.ts'
import { CommentComposer } from './CommentComposer.tsx'
import { useDeleteComment, useEditComment } from './mutations.ts'
import styles from './Activity.module.css'

/** One comment: who, when, the sanitised body — and, for its author (or an admin), edit and delete. */
export function CommentItem({
  comment,
  issueKey,
  projectKey,
  canEdit,
  canDelete,
}: {
  comment: Comment
  issueKey: string
  projectKey: string
  canEdit: boolean
  canDelete: boolean
}) {
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const edit = useEditComment(issueKey)
  const remove = useDeleteComment(issueKey)
  const author = comment.author

  return (
    <li className={styles.item}>
      <Avatar name={author.displayName} initials={author.initials} color={avatarColor(author.avatarColor)} />
      <div className={styles.body}>
        <p className={styles.meta}>
          <strong>{author.displayName}</strong>{' '}
          <time dateTime={comment.createdAt} title={new Date(comment.createdAt).toLocaleString()}>
            {timeAgo(comment.createdAt)}
          </time>
          {comment.editedAt && <span className={styles.edited}> · edited</span>}
        </p>
        {editing ? (
          <EditComment
            comment={comment}
            projectKey={projectKey}
            onSave={async (bodyHtml) => {
              await edit.mutateAsync({ comment, bodyHtml })
              setEditing(false)
            }}
            onCancel={() => setEditing(false)}
          />
        ) : (
          <SafeHtml html={comment.bodyHtml} />
        )}
        {!editing && (canEdit || canDelete) && (
          <div className={styles.itemActions}>
            {confirming ? (
              <>
                <span>Delete this comment?</span>
                <Button
                  size="sm"
                  variant="danger"
                  disabled={remove.isPending}
                  onClick={() => remove.mutate(comment)}
                >
                  Delete
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirming(false)}>
                  Keep
                </Button>
              </>
            ) : (
              <>
                {canEdit && (
                  <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>
                    Edit
                  </Button>
                )}
                {canDelete && (
                  <Button size="sm" variant="ghost" onClick={() => setConfirming(true)}>
                    Delete
                  </Button>
                )}
              </>
            )}
          </div>
        )}
      </div>
    </li>
  )
}

function EditComment({
  comment,
  projectKey,
  onSave,
  onCancel,
}: {
  comment: Comment
  projectKey: string
  onSave: (bodyHtml: string) => Promise<void>
  onCancel: () => void
}) {
  // the stored HTML back to what the composer edits: text with "@Name", and who those names are
  const [initial] = useState(() => fromCommentHtml(comment.bodyHtml))
  return (
    <CommentComposer
      projectKey={projectKey}
      label="Edit comment"
      submitLabel="Save"
      initialText={initial.text}
      initialMentions={initial.mentions}
      onSubmit={onSave}
      onCancel={onCancel}
    />
  )
}

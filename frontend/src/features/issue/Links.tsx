import { Link } from '@tanstack/react-router'
import { useActionState, useState } from 'react'
import { isApiError, messageOf } from '@/shared/api/errors.ts'
import type { IssueDetails, LinkType, LinkView } from '@/shared/api/types.ts'
import { issueType } from '@/shared/lib/enums.ts'
import { focusOnMount } from '@/shared/lib/focus.ts'
import { text } from '@/shared/lib/forms.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { FormAlert, SubmitButton } from '@/shared/ui/Form.tsx'
import { Lozenge } from '@/shared/ui/Lozenge.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { TextInput } from '@/shared/ui/TextInput.tsx'
import { TypeIcon } from '@/shared/ui/TypeIcon.tsx'
import { useAddLink, useRemoveLink } from './mutations.ts'
import styles from './IssueSections.module.css'

const LINK_TYPES: { value: LinkType; label: string }[] = [
  { value: 'blocks', label: 'blocks' },
  { value: 'relates', label: 'relates to' },
  { value: 'duplicates', label: 'duplicates' },
]

/** Linked issues, grouped by how they relate as read from this issue ("blocks", "is blocked by", …). */
export function Links({ issue, canEdit }: { issue: IssueDetails; canEdit: boolean }) {
  const [adding, setAdding] = useState(false)
  const remove = useRemoveLink(issue.key)
  // by label, in the api's order ("blocks" before "is blocked by" …)
  const groups = new Map<string, LinkView[]>()
  for (const link of issue.links) {
    groups.set(link.label, [...(groups.get(link.label) ?? []), link])
  }

  return (
    <section className={styles.section} aria-labelledby={`${issue.key}-links`}>
      <header className={styles.sectionHeader}>
        <h3 id={`${issue.key}-links`} className={styles.sectionTitle}>
          Linked issues
        </h3>
        {canEdit && !adding && (
          <Button size="sm" variant="ghost" icon="link" onClick={() => setAdding(true)}>
            Link issue
          </Button>
        )}
      </header>
      {issue.links.length === 0 && !adding && <p className={styles.muted}>No links.</p>}
      {[...groups].map(([label, links]) => (
        <div key={label} className={styles.group}>
          <h4 className={styles.groupLabel}>{label}</h4>
          <ul role="list" className={styles.list}>
            {links.map((link) => (
              <li
                key={link.id}
                className={styles.row}
                data-done={link.issue.statusCategory === 'done' || undefined}
              >
                <TypeIcon type={issueType(link.issue.type)} />
                <Link
                  to="/p/$projectKey/issues/$issueKey"
                  params={{
                    projectKey: link.issue.key.split('-')[0] ?? issue.projectKey,
                    issueKey: link.issue.key,
                  }}
                  className={styles.rowKey}
                >
                  {link.issue.key}
                </Link>
                <span className={styles.rowSummary}>{link.issue.summary}</span>
                <Lozenge tone={link.issue.statusCategory === 'done' ? 'done' : 'todo'}>
                  {link.issue.statusName}
                </Lozenge>
                {canEdit && (
                  <Button
                    size="sm"
                    variant="ghost"
                    icon="close"
                    aria-label={`Remove link: ${label} ${link.issue.key}`}
                    disabled={remove.isPending}
                    onClick={() => remove.mutate(link.id)}
                  />
                )}
              </li>
            ))}
          </ul>
        </div>
      ))}
      {adding && <AddLink issue={issue} onDone={() => setAdding(false)} />}
    </section>
  )
}

function AddLink({ issue, onDone }: { issue: IssueDetails; onDone: () => void }) {
  const add = useAddLink(issue.key)
  const [error, action] = useActionState(async (_previous: string | undefined, form: FormData) => {
    const targetKey = text(form, 'targetKey').trim().toUpperCase()
    if (!/^[A-Z]{2,6}-\d+$/.test(targetKey)) {
      return 'Enter an issue key like MAR-12.'
    }
    try {
      await add.mutateAsync({ targetKey, type: text(form, 'type') as LinkType })
      onDone()
      return undefined
    } catch (failure) {
      if (isApiError(failure, 'NOT_FOUND')) {
        return `There is no ${targetKey} you can see.`
      }
      return messageOf(failure)
    }
  }, undefined)

  return (
    <form action={action} className={styles.inlineForm} noValidate>
      <FormAlert message={error} />
      <Select name="type" defaultValue="relates" aria-label="Link type">
        {LINK_TYPES.map((type) => (
          <option key={type.value} value={type.value}>
            {issue.key} {type.label}…
          </option>
        ))}
      </Select>
      <TextInput name="targetKey" placeholder="MAR-12" aria-label="Issue key to link" ref={focusOnMount} />
      <SubmitButton pendingLabel="Linking…">Link</SubmitButton>
      <Button variant="ghost" onClick={onDone}>
        Cancel
      </Button>
    </form>
  )
}

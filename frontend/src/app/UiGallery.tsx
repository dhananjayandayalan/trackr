import { useState, type ReactNode } from 'react'
import { Avatar, type AvatarColor } from '@/shared/ui/Avatar.tsx'
import { Button, type ButtonProps } from '@/shared/ui/Button.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { icons, type IconName } from '@/shared/ui/icons.ts'
import { Kbd } from '@/shared/ui/Kbd.tsx'
import { Lozenge, type LozengeTone } from '@/shared/ui/Lozenge.tsx'
import { PriorityIcon, type Priority } from '@/shared/ui/PriorityIcon.tsx'
import { TypeIcon, type IssueType } from '@/shared/ui/TypeIcon.tsx'
import { AvatarGroup } from '@/shared/ui/AvatarGroup.tsx'
import { EmptyState } from '@/shared/ui/EmptyState.tsx'
import { Field } from '@/shared/ui/Field.tsx'
import { Progress } from '@/shared/ui/Progress.tsx'
import { ProjectTile } from '@/shared/ui/ProjectTile.tsx'
import { Segmented } from '@/shared/ui/Segmented.tsx'
import { Select } from '@/shared/ui/Select.tsx'
import { Tabs } from '@/shared/ui/Tabs.tsx'
import { Textarea, TextInput } from '@/shared/ui/TextInput.tsx'
import { showToast, Toaster } from '@/shared/ui/Toast.tsx'
import styles from './UiGallery.module.css'

const variants: NonNullable<ButtonProps['variant']>[] = ['primary', 'accent', 'secondary', 'ghost', 'danger']
const sizes: NonNullable<ButtonProps['size']>[] = ['sm', 'md', 'lg']
const tones: LozengeTone[] = ['todo', 'progress', 'review', 'done', 'blocked']
const types: IssueType[] = ['story', 'bug', 'task', 'epic', 'subtask']
const priorities: Priority[] = ['highest', 'high', 'medium', 'low', 'lowest']
const colors: AvatarColor[] = ['pink', 'cyan', 'lime', 'yellow', 'orange', 'violet']

/**
 * Every component in every variant and state on one page — a hand-made Storybook (dev only, at /_ui).
 * Change a token or a module and see every consumer at once.
 */
export function UiGallery() {
  const [pressed, setPressed] = useState(false)
  const [tab, setTab] = useState<'one' | 'two' | 'three'>('one')
  const [view, setView] = useState<'grid' | 'list'>('grid')
  const people = (['pink', 'cyan', 'lime', 'yellow', 'orange'] as const).map((color, index) => ({
    id: color,
    displayName: `Person ${index + 1}`,
    initials: `P${index + 1}`,
    avatarColor: color,
  }))

  return (
    <main className={styles.root}>
      <header className={styles.header}>
        <h1>VOLT · UI gallery</h1>
        <p>
          Every component × every variant. Dev only; the app is at <a href="/">/</a>.
        </p>
      </header>

      <Section title="Button">
        {sizes.map((size) => (
          <div key={size} className={styles.row}>
            <span className={styles.rowLabel}>{size}</span>
            {variants.map((variant) => (
              <Button key={variant} variant={variant} size={size}>
                {variant}
              </Button>
            ))}
            <Button size={size} disabled>
              disabled
            </Button>
            <Button size={size} variant="primary" icon="plus">
              Create
            </Button>
            <Button size={size} icon="bell" aria-label="Notifications" />
          </div>
        ))}
        <div className={styles.row}>
          <span className={styles.rowLabel}>toggle</span>
          <Button
            icon="user"
            aria-pressed={pressed}
            onClick={() => {
              setPressed(!pressed)
            }}
          >
            Only my issues
          </Button>
          <Button variant="ghost" icon="more" aria-label="More actions" />
        </div>
      </Section>

      <Section title="Lozenge">
        <div className={styles.row}>
          {tones.map((tone) => (
            <Lozenge key={tone} tone={tone}>
              {tone === 'progress' ? 'In progress' : tone === 'review' ? 'In review' : tone}
            </Lozenge>
          ))}
        </div>
      </Section>

      <Section title="TypeIcon · PriorityIcon">
        <div className={styles.row}>
          {types.map((type) => (
            <span key={type} className={styles.labelled}>
              <TypeIcon type={type} /> {type}
            </span>
          ))}
        </div>
        <div className={styles.row}>
          {priorities.map((priority) => (
            <span key={priority} className={styles.labelled}>
              <PriorityIcon priority={priority} /> {priority}
            </span>
          ))}
        </div>
      </Section>

      <Section title="Avatar">
        {(['sm', 'md', 'lg'] as const).map((size) => (
          <div key={size} className={styles.row}>
            <span className={styles.rowLabel}>{size}</span>
            {colors.map((color) => (
              <Avatar key={color} name={`A ${color} person`} initials="AL" color={color} size={size} />
            ))}
          </div>
        ))}
      </Section>

      <Section title="Kbd">
        <div className={styles.row}>
          <Kbd>/</Kbd>
          <Kbd>c</Kbd>
          <span className={styles.labelled}>
            <Kbd>g</Kbd> then <Kbd>b</Kbd>
          </span>
          <span className={styles.labelled}>
            <Kbd>Ctrl</Kbd> + <Kbd>K</Kbd>
          </span>
        </div>
      </Section>

      <Section title="Field · TextInput · Select · Textarea">
        <div className={styles.fields}>
          <Field label="Name" hint="Shown on cards.">
            {(control) => <TextInput {...control} placeholder="Marketing site" />}
          </Field>
          <Field label="Key" error="MAR is already used in this workspace.">
            {(control) => <TextInput {...control} defaultValue="MAR" />}
          </Field>
          <Field label="Access">
            {(control) => (
              <Select {...control}>
                <option>Limited</option>
                <option>Open</option>
              </Select>
            )}
          </Field>
          <Field label="Disabled">{(control) => <TextInput {...control} disabled value="read only" />}</Field>
          <Field label="Description">{(control) => <Textarea {...control} />}</Field>
        </div>
      </Section>

      <Section title="Tabs · Segmented">
        <Tabs
          label="Example"
          value={tab}
          tabs={[
            { value: 'one', label: 'Assigned to me', count: 12 },
            { value: 'two', label: 'Worked on' },
            { value: 'three', label: 'Starred' },
          ]}
          onChange={setTab}
        >
          <p>Panel {tab}</p>
        </Tabs>
        <Segmented
          label="View"
          value={view}
          options={[
            { value: 'grid', label: 'Grid', icon: 'dashboard' },
            { value: 'list', label: 'List', icon: 'issues' },
          ]}
          onChange={setView}
        />
      </Section>

      <Section title="Progress · AvatarGroup · ProjectTile · Toast">
        <div className={styles.row}>
          {[0, 18, 64, 100].map((value) => (
            <span key={value} className={styles.progress}>
              <Progress value={value} label={`${value}%`} />
            </span>
          ))}
        </div>
        <div className={styles.row}>
          <AvatarGroup people={people} />
          <AvatarGroup people={people.slice(0, 2)} size="md" />
          <ProjectTile projectKey="MAR" color="pink" size="sm" />
          <ProjectTile projectKey="OPS" color="cyan" />
          <ProjectTile projectKey="BUG" color="orange" size="lg" />
        </div>
        <div className={styles.row}>
          <Button onClick={() => showToast('Saved.', 'success')}>Success toast</Button>
          <Button onClick={() => showToast("Couldn't move — reverted.", 'error')}>Error toast</Button>
          <Button onClick={() => showToast('Creating issues arrives in phase 15.')}>Info toast</Button>
        </div>
        <Toaster />
      </Section>

      <Section title="EmptyState">
        <EmptyState icon="issues" title="All clear" action={<Button variant="primary">Create issue</Button>}>
          Nothing open is assigned to you.
        </EmptyState>
      </Section>

      <Section title="Icon">
        <ul role="list" className={styles.icons}>
          {(Object.keys(icons) as IconName[]).map((name) => (
            <li key={name} className={styles.icon}>
              <Icon name={name} size="lg" />
              <span>{name}</span>
            </li>
          ))}
        </ul>
      </Section>
    </main>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>{title}</h2>
      {children}
    </section>
  )
}

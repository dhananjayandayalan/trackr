import { useState, type ReactNode } from 'react'
import { Avatar, type AvatarColor } from '@/shared/ui/Avatar.tsx'
import { Button, type ButtonProps } from '@/shared/ui/Button.tsx'
import { Icon } from '@/shared/ui/Icon.tsx'
import { icons, type IconName } from '@/shared/ui/icons.ts'
import { Kbd } from '@/shared/ui/Kbd.tsx'
import { Lozenge, type LozengeTone } from '@/shared/ui/Lozenge.tsx'
import { PriorityIcon, type Priority } from '@/shared/ui/PriorityIcon.tsx'
import { TypeIcon, type IssueType } from '@/shared/ui/TypeIcon.tsx'
import { AppShell } from './AppShell.tsx'
import { ShellPreview } from './ShellPreview.tsx'
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

      <Section title="AppShell · 40rem wide (container query: sidebar becomes a drawer)">
        <div className={styles.narrow}>
          <AppShell>
            <ShellPreview />
          </AppShell>
        </div>
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

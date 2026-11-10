import type { ComponentPropsWithRef } from 'react'
import styles from './SafeHtml.module.css'

type SafeHtmlProps = Omit<ComponentPropsWithRef<'div'>, 'children' | 'dangerouslySetInnerHTML'> & {
  /** HTML the api sanitised with its allow-list (comment bodies, descriptions) — never anything else */
  html: string
}

/**
 * The only place the app renders HTML (09-security §4). React escapes every string it renders, which is what
 * makes it safe by default; dangerouslySetInnerHTML switches that off. It is allowed here, and only here,
 * because the api sanitised this HTML with an allow-list before storing it (p, br, strong, em, code, pre,
 * lists, links, mention spans) — so there is no script, event handler or javascript: link left to run.
 * One component means one place to audit: grep for dangerouslySetInnerHTML finds this file and nothing else.
 */
export function SafeHtml({ html, className, ...rest }: SafeHtmlProps) {
  return (
    <div
      className={className ? `${styles.root} ${className}` : styles.root}
      // sanitised server-side by HtmlSanitizer (OWASP allow-list)
      dangerouslySetInnerHTML={{ __html: html }}
      {...rest}
    />
  )
}

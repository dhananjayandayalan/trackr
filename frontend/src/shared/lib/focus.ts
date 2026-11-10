/**
 * A callback ref that focuses its element once, when it mounts: for a control that appears because the user
 * asked for it ("Add subtask" shows its input). jsx-a11y forbids the autoFocus prop because on page load it
 * moves focus away from where a screen reader user is; focusing in answer to their own click is expected.
 */
export function focusOnMount(element: HTMLElement | null) {
  element?.focus()
}

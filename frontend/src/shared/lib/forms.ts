/** A form field's text. FormData.get also returns File for file inputs (or null): anything but text is "". */
export function text(form: FormData, name: string): string {
  const value = form.get(name)
  return typeof value === 'string' ? value : ''
}

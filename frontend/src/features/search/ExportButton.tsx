import { useState } from 'react'
import { api } from '@/shared/api/client.ts'
import { messageOf } from '@/shared/api/errors.ts'
import { Button } from '@/shared/ui/Button.tsx'
import { useToast } from '@/shared/ui/Toast.tsx'

/**
 * CSV export (03-api §3.8): POST the query, read the streamed response as a Blob, and hand it to the browser as
 * a download through a temporary object URL. A POST can't be a plain <a href download>, hence the detour.
 */
export function ExportButton({ tql }: { tql: string }) {
  const [busy, setBusy] = useState(false)
  const toast = useToast()

  async function download() {
    setBusy(true)
    try {
      const { data } = await api.POST('/api/v1/search/export', { body: { tql }, parseAs: 'blob' })
      if (!data) {
        return
      }
      const url = URL.createObjectURL(data)
      const link = document.createElement('a')
      link.href = url
      link.download = 'issues.csv'
      link.click()
      // the click has started the download; the URL can go once the browser has taken it
      setTimeout(() => {
        URL.revokeObjectURL(url)
      }, 1000)
    } catch (error) {
      toast(`Export failed: ${messageOf(error)}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Button icon="external" size="sm" disabled={busy} onClick={() => void download()}>
      {busy ? 'Exporting…' : 'Export CSV'}
    </Button>
  )
}

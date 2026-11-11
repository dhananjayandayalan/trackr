import { useShortcut } from '@/app/keyboard.ts'

/**
 * A single-key shortcut for the whole page, like `c` for Create (00-product §3.13). Since phase 17 it is a thin
 * name for `useShortcut` in app/keyboard.ts: one document listener for every shortcut, which also knows
 * sequences (`g b`) and ignores keys while the user is typing or a modal dialog is open.
 */
export function useKeyboardShortcut(key: string, onPress: () => void) {
  useShortcut(key, onPress)
}

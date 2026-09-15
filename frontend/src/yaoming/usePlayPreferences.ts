import { computed, onScopeDispose, ref } from 'vue'

const CHANGE_EVENT = 'yaoming-play-preference-change'
function readBoolean(key: string, fallback: boolean) {
  try {
    const saved = localStorage.getItem(key)
    return saved === 'true' ? true : saved === 'false' ? false : fallback
  } catch { return fallback }
}

/** Separate component instances share settings without sharing player identities. */
function useStoredBoolean(key: string, fallback: boolean) {
  const value = ref(readBoolean(key, fallback))
  const syncLocal = (event: Event) => {
    const detail = (event as CustomEvent<{ key?: string; value?: boolean }>).detail
    if (detail?.key === key && typeof detail.value === 'boolean') value.value = detail.value
  }
  const syncOtherTab = (event: StorageEvent) => {
    if (event.key === key || event.key === null) value.value = readBoolean(key, fallback)
  }
  window.addEventListener(CHANGE_EVENT, syncLocal)
  window.addEventListener('storage', syncOtherTab)
  onScopeDispose(() => { window.removeEventListener(CHANGE_EVENT, syncLocal); window.removeEventListener('storage', syncOtherTab) })
  return computed({ get: () => value.value, set: (next: boolean) => {
    if (value.value === next) return
    value.value = next
    try { localStorage.setItem(key, String(next)) } catch { /* Restricted storage still permits this session's setting. */ }
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: { key, value: next } }))
  } })
}

export function usePlayPreferences() {
  return { autoDraw: useStoredBoolean('yaoming.autoDraw', true), quickDiscard: useStoredBoolean('yaoming.quickDiscard', false) }
}

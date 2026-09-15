import { onMounted, onUnmounted, watch } from 'vue'
import type { Action, Tile } from './types'

export interface TableKeyboardContext {
  enabled: boolean; canDiscard: boolean; busy: boolean; expired: boolean; trustee: boolean;
  overlayOpen: boolean; status: string; tiles: Tile[]; selectedId: string | null; actions: Action[];
  /** Include room/seat and the accepted server revision, never the local selected tile. */
  revisionKey: string;
}
export interface TableKeyboardCallbacks {
  select: (tile: Tile) => void;
  discard: (tile: Tile) => void;
  pass: (action: Action) => void;
}

const interactive = 'button,summary,a[href],label,[role="button"],[role="link"],[role="checkbox"],'
  + '[role="radio"],[role="switch"],[role="tab"],[role="menuitem"],[role="option"],[role="slider"],[role="spinbutton"]'

function eventElements(event: KeyboardEvent): Element[] {
  const elements = event.composedPath().filter((target): target is Element => target instanceof Element)
  if (document.activeElement instanceof Element) elements.push(document.activeElement)
  return elements
}
function editable(element: Element): boolean {
  if (element.closest('input,textarea,select')) return true
  for (let ancestor: Element | null = element; ancestor; ancestor = ancestor.parentElement) {
    const value = ancestor.getAttribute('contenteditable')
    if (value !== null && value.toLowerCase() !== 'false') return true
  }
  return false
}
function visible(element: Element): boolean {
  for (let ancestor: Element | null = element; ancestor; ancestor = ancestor.parentElement) {
    if (ancestor.hasAttribute('hidden') || ancestor.getAttribute('aria-hidden') === 'true') return false
    if (ancestor.tagName === 'DIALOG' && !ancestor.hasAttribute('open')) return false
    const style = getComputedStyle(ancestor)
    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return false
  }
  return true
}
function modalOpen(): boolean {
  return [...document.querySelectorAll('.ym-overlay,[aria-modal="true"]')].some(visible)
}

/** Selection never clicks a tile, so quick-discard preference cannot turn A/D into a play. */
export function useTableKeyboard(getContext: () => TableKeyboardContext, callbacks: TableKeyboardCallbacks): void {
  let submittedRevision: string | null = null
  // A failed request may finish without advancing the server revision. Keep the initial
  // synchronous duplicate guard, but permit an intentional retry once that request settles.
  watch(() => getContext().busy, (busy, previous) => {
    if (previous && !busy) submittedRevision = null
  }, { flush: 'sync' })
  function keydown(event: KeyboardEvent) {
    if (event.defaultPrevented) return
    const key = event.key.toLowerCase()
    if (!['a', 'd', 'enter', ' ', 'spacebar'].includes(key)) return
    const context = getContext()
    if (!context.enabled) return
    const elements = eventElements(event)
    if (elements.some(editable)) return
    if (context.overlayOpen || modalOpen()) return
    const ownTileButton = elements.map(element => element.closest<HTMLButtonElement>('button.ym-hand-tile')).find(Boolean)
    const focusedControl = elements.some(element => !!element.closest(interactive))
    const space = key === ' ' || key === 'spacebar'
    if (event.isComposing || event.keyCode === 229 || event.ctrlKey || event.altKey || event.metaKey) return
    // Only a focused hand tile may delegate Enter to the global discard shortcut.
    // Cancel its native click even when a held/disabled key cannot submit a new action.
    if (key === 'enter' && ownTileButton) event.preventDefault()
    // Space confirms the selected discard or passes a reaction; it must never activate
    // a previously focused hand/kong button, including while busy, held or unselected.
    if (space && (context.status === 'NEED_DISCARD' && (context.canDiscard
      || context.actions.some(action => action.type === 'DISCARD' && action.tileIds.length === 1))
      || context.status === 'REACTION' && context.actions.some(action => action.type === 'PASS' && action.tileIds.length === 0))) event.preventDefault()
    if (event.repeat) return
    if (context.busy || context.expired || context.trustee) return
    if (key === 'enter' && focusedControl && !(ownTileButton && !ownTileButton.disabled)) return
    if (submittedRevision !== null && submittedRevision !== context.revisionKey) submittedRevision = null

    if (key === 'a' || key === 'd') {
      if (!context.canDiscard || context.status !== 'NEED_DISCARD') return
      const tiles = context.tiles.filter(tile => context.actions.some(action => action.type === 'DISCARD'
        && action.tileIds.length === 1 && action.tileIds[0] === tile.id))
      if (!tiles.length) return
      const selected = tiles.findIndex(tile => tile.id === context.selectedId)
      const next = selected < 0 ? key === 'a' ? tiles.length - 1 : 0
        : (selected + (key === 'a' ? -1 : 1) + tiles.length) % tiles.length
      event.preventDefault(); callbacks.select(tiles[next]); return
    }

    if (key === 'enter' || space && context.status === 'NEED_DISCARD') {
      if (!context.canDiscard || context.status !== 'NEED_DISCARD') return
      const selected = context.tiles.find(tile => tile.id === context.selectedId)
      if (!selected || !context.actions.some(action => action.type === 'DISCARD'
        && action.tileIds.length === 1 && action.tileIds[0] === selected.id)) return
      event.preventDefault()
      if (submittedRevision === context.revisionKey) return
      submittedRevision = context.revisionKey
      callbacks.discard(selected)
    } else {
      if (context.status !== 'REACTION') return
      const pass = context.actions.find(action => action.type === 'PASS' && action.tileIds.length === 0)
      if (!pass) return
      event.preventDefault()
      if (submittedRevision === context.revisionKey) return
      submittedRevision = context.revisionKey
      callbacks.pass(pass)
    }
  }
  onMounted(() => document.addEventListener('keydown', keydown))
  onUnmounted(() => document.removeEventListener('keydown', keydown))
}

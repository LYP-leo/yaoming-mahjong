import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, reactive } from 'vue'
import { useTableKeyboard, type TableKeyboardContext } from './useTableKeyboard'
import type { Action, Tile } from './types'

const hand: Tile[] = Array.from({ length: 13 }, (_, index) => ({ id: `held-${index}`, suit: 'BAMBOO', rank: index % 9 + 1, label: `${index % 9 + 1}条` }))
const drawn: Tile = { ...hand[0], id: 'drawn-same-face' }
const pass: Action = { type: 'PASS', label: '过', tileIds: [] }
const wrappers: VueWrapper[] = []
const elements: Element[] = []

function context(overrides: Partial<TableKeyboardContext> = {}): TableKeyboardContext {
  const tiles = [...hand, drawn]
  return { enabled: true, canDiscard: true, busy: false, expired: false, trustee: false, overlayOpen: false,
    status: 'NEED_DISCARD', tiles, selectedId: null,
    actions: tiles.map(tile => ({ type: 'DISCARD', label: `打出${tile.label}`, tileIds: [tile.id] })),
    revisionKey: 'room1:p1:1', ...overrides }
}
function harness(initial = context()) {
  const state = reactive(initial)
  const callbacks = { select: vi.fn((tile: Tile) => { state.selectedId = tile.id }), discard: vi.fn(), pass: vi.fn() }
  const component = defineComponent({ setup() { useTableKeyboard(() => state, callbacks); return () => h('div') } })
  const wrapper = mount(component); wrappers.push(wrapper)
  return { state, callbacks, wrapper }
}
function key(value: string, init: KeyboardEventInit & { keyCode?: number } = {}, target: EventTarget = document) {
  const event = new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...init })
  target.dispatchEvent(event); return event
}
function element(html: string) {
  const host = document.createElement('div'); host.innerHTML = html; document.body.append(host); elements.push(host)
  return host.firstElementChild as HTMLElement
}
beforeEach(() => { vi.restoreAllMocks(); localStorage.clear() })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); elements.splice(0).forEach(node => node.remove()) })

describe('physical display-order keyboard selection', () => {
  it('selects first with D and last with A when nothing is selected, and wraps both boundaries', () => {
    const { state, callbacks } = harness()
    expect(key('d').defaultPrevented).toBe(true); expect(state.selectedId).toBe(hand[0].id)
    key('a'); expect(state.selectedId).toBe(drawn.id)
    key('d'); expect(state.selectedId).toBe(hand[0].id)
    state.selectedId = null; key('A'); expect(state.selectedId).toBe(drawn.id)
    key('a'); expect(state.selectedId).toBe(hand[12].id)
    expect(callbacks.discard).not.toHaveBeenCalled(); expect(callbacks.pass).not.toHaveBeenCalled()
  })

  it('visits all fourteen physical entities, including a same-faced separate drawn tile', () => {
    const { state, callbacks } = harness(); const selected: string[] = []
    for (let index = 0; index < 14; index++) { key('d'); selected.push(state.selectedId!) }
    expect(selected).toEqual([...hand, drawn].map(tile => tile.id))
    expect(new Set(selected).size).toBe(14)
    expect(callbacks.select).toHaveBeenLastCalledWith(drawn)
    expect(callbacks.discard).not.toHaveBeenCalled()
  })

  it('does not sort the caller display order and skips tiles lacking an exact legal discard', () => {
    const initial = context({ tiles: [drawn, hand[8], hand[0]] })
    initial.actions = initial.actions.filter(action => action.tileIds[0] !== hand[8].id)
    const { state, callbacks } = harness(initial)
    key('d'); expect(state.selectedId).toBe(drawn.id)
    key('d'); expect(state.selectedId).toBe(hand[0].id)
    expect(state.tiles.map(tile => tile.id)).toEqual([drawn.id, hand[8].id, hand[0].id])
    expect(callbacks.discard).not.toHaveBeenCalled()
  })

  it.each([false, true])('only selects with A/D regardless of quick-discard preference %s', quick => {
    localStorage.setItem('yaoming.quickDiscard', String(quick))
    const { callbacks } = harness()
    const button = element('<button class="ym-hand-tile">手牌</button>')
    const click = vi.fn(); button.addEventListener('click', click); button.focus()
    key('d', {}, button); key('d', {}, button)
    expect(callbacks.select).toHaveBeenCalledTimes(2)
    expect(callbacks.discard).not.toHaveBeenCalled(); expect(click).not.toHaveBeenCalled()
  })

  it('ignores selection when hand/action list is empty or this is not the discard phase', () => {
    const { state, callbacks } = harness(context({ tiles: [] }))
    key('a'); state.tiles = [...hand]; state.actions = []; key('d')
    state.actions = context().actions; state.status = 'NEED_DRAW'; key('d')
    expect(callbacks.select).not.toHaveBeenCalled()
  })
})

describe('explicit legal keyboard submissions and per-revision lock', () => {
  it.each(['Enter', ' ', 'Spacebar'])('%s requires an existing selection and submits the selected physical tile, not an equal face', shortcut => {
    const { state, callbacks } = harness()
    key(shortcut); expect(callbacks.discard).not.toHaveBeenCalled()
    state.selectedId = drawn.id
    expect(key(shortcut).defaultPrevented).toBe(true)
    expect(callbacks.discard).toHaveBeenCalledExactlyOnceWith(drawn)
    expect(callbacks.pass).not.toHaveBeenCalled()
  })

  it('never substitutes WIN/CHI/multi-ID DISCARD for a legal single-tile discard', () => {
    const { state, callbacks } = harness(context({ selectedId: drawn.id,
      actions: [{ type: 'WIN', label: '自摸', tileIds: [] }, { type: 'CHI', label: '吃', tileIds: [drawn.id] },
        { type: 'DISCARD', label: '非法多张', tileIds: [drawn.id, hand[0].id] }] }))
    key('Enter'); key(' '); key('d')
    state.selectedId = 'missing-id'; state.actions = context().actions; key('Enter'); key(' ')
    expect(callbacks.discard).not.toHaveBeenCalled(); expect(callbacks.select).not.toHaveBeenCalled()
  })

  it.each([['Enter', ' '], [' ', 'Enter']])('shares one submission lock when alternating %s then %s', (first, second) => {
    const { state, callbacks } = harness(context({ selectedId: drawn.id }))
    key(first); key(second); key(first); key('d'); key(second)
    expect(callbacks.discard).toHaveBeenCalledExactlyOnceWith(drawn)
    state.busy = true; key(first); state.busy = false
    key(second); key(first)
    expect(callbacks.discard).toHaveBeenCalledTimes(2)
    expect(callbacks.discard).toHaveBeenLastCalledWith(hand[0])
    state.revisionKey = 'room1:p1:2'; state.selectedId = hand[7].id
    key(' '); key('Enter')
    expect(callbacks.discard).toHaveBeenCalledTimes(3)
    expect(callbacks.discard).toHaveBeenLastCalledWith(hand[7])
  })

  it.each(['Enter', ' '])('%s discards the current selection even when a different physical hand tile retains focus', shortcut => {
    const { state, callbacks } = harness(context({ selectedId: hand[8].id }))
    const control = element(`<button class="ym-hand-tile" data-tile-id="${drawn.id}"><span>摸牌</span></button>`), click = vi.fn()
    control.addEventListener('click', click); control.focus()
    const event = key(shortcut, {}, control.firstElementChild!)
    if (!event.defaultPrevented) control.click()
    expect(event.defaultPrevented).toBe(true)
    expect(callbacks.discard).toHaveBeenCalledExactlyOnceWith(hand[8])
    expect(state.selectedId).toBe(hand[8].id)
    expect(callbacks.select).not.toHaveBeenCalled(); expect(callbacks.pass).not.toHaveBeenCalled(); expect(click).not.toHaveBeenCalled()
  })

  it('Space on a focused kong button discards the selected tile without executing the kong', () => {
    const initial = context({ selectedId: drawn.id })
    initial.actions.push({ type: 'KONG', label: '暗杠', tileIds: hand.slice(0, 4).map(tile => tile.id) })
    const { callbacks } = harness(initial)
    const control = element('<button class="ym-call-option"><span>暗杠</span></button>'), click = vi.fn()
    control.addEventListener('click', click); control.focus()
    const event = key(' ', {}, control.firstElementChild!)
    if (!event.defaultPrevented) control.click()
    expect(event.defaultPrevented).toBe(true)
    expect(callbacks.discard).toHaveBeenCalledExactlyOnceWith(drawn)
    expect(callbacks.pass).not.toHaveBeenCalled(); expect(click).not.toHaveBeenCalled()
  })

  it.each([null, 'missing-id', hand[8].id])('Space with invalid selection %s suppresses scrolling and focused button activation without discarding', selectedId => {
    const { callbacks } = harness(context({ selectedId, actions: [{ type: 'DISCARD', label: '出牌', tileIds: [drawn.id] }] }))
    const control = element('<button class="ym-call-option">杠</button>'), click = vi.fn()
    control.addEventListener('click', click); control.focus()
    const event = key(' ', {}, control)
    if (!event.defaultPrevented) control.click()
    expect(event.defaultPrevented).toBe(true)
    expect(callbacks.discard).not.toHaveBeenCalled(); expect(callbacks.pass).not.toHaveBeenCalled(); expect(click).not.toHaveBeenCalled()
  })

  it.each(['repeat', 'busy', 'expired', 'trustee'] as const)('Space keeps a discard-phase button from activating while guarded by %s', guard => {
    const { callbacks } = harness(context({ selectedId: drawn.id, ...(guard === 'repeat' ? {} : { [guard]: true }) }))
    const control = element('<button>杠</button>'), click = vi.fn()
    control.addEventListener('click', click); control.focus()
    const event = key(' ', guard === 'repeat' ? { repeat: true } : {}, control)
    if (!event.defaultPrevented) control.click()
    expect(event.defaultPrevented).toBe(true)
    expect(callbacks.discard).not.toHaveBeenCalled(); expect(callbacks.pass).not.toHaveBeenCalled(); expect(click).not.toHaveBeenCalled()
  })

  it('REACTION Space only passes even with stale discard eligibility and a selected legal discard', () => {
    const initial = context({ status: 'REACTION', canDiscard: true, selectedId: drawn.id })
    initial.actions.push(pass)
    const { state, callbacks } = harness(initial)
    key(' ')
    expect(callbacks.pass).toHaveBeenCalledExactlyOnceWith(pass)
    expect(callbacks.discard).not.toHaveBeenCalled()
    state.revisionKey = 'room1:p1:2'; state.actions = context().actions
    key(' '); key('Enter')
    expect(callbacks.pass).toHaveBeenCalledTimes(1); expect(callbacks.discard).not.toHaveBeenCalled()
  })

  it.each(['WAITING', 'NEED_DRAW', 'HAND_END', 'MATCH_END'])('does not discard or pass from %s even with stale legal actions', status => {
    const initial = context({ status, selectedId: drawn.id }); initial.actions.push(pass)
    const { callbacks } = harness(initial)
    key(' '); key('Enter')
    expect(callbacks.discard).not.toHaveBeenCalled(); expect(callbacks.pass).not.toHaveBeenCalled()
  })

  it('takes only an empty-ID PASS during REACTION, preventing space scroll', () => {
    const { state, callbacks } = harness(context({ status: 'REACTION', canDiscard: false, actions: [pass] }))
    expect(key(' ').defaultPrevented).toBe(true)
    expect(callbacks.pass).toHaveBeenCalledExactlyOnceWith(pass)
    state.revisionKey = 'room1:p1:2'; state.status = 'NEED_DRAW'; key(' ')
    state.status = 'REACTION'; state.actions = [{ ...pass, tileIds: [drawn.id] }]; key(' ')
    state.actions = [{ type: 'WIN', label: '点和', tileIds: [] }]; key('Spacebar')
    expect(callbacks.pass).toHaveBeenCalledTimes(1); expect(callbacks.discard).not.toHaveBeenCalled()
  })

  it('prevents a held PASS key from scrolling while repeating or awaiting submission, without sending twice', () => {
    const { state, callbacks } = harness(context({ status: 'REACTION', canDiscard: false, actions: [pass] }))
    key(' ')
    expect(key(' ', { repeat: true }).defaultPrevented).toBe(true)
    state.busy = true
    expect(key(' ').defaultPrevented).toBe(true)
    expect(callbacks.pass).toHaveBeenCalledExactlyOnceWith(pass)
    expect(key(' ', { ctrlKey: true }).defaultPrevented).toBe(false)
  })

  it('blocks repeated submissions in one revision even when selection changes, releasing only for new server context', () => {
    const { state, callbacks } = harness(context({ selectedId: hand[0].id }))
    key('Enter'); key('Enter'); key('d'); key('Enter')
    state.busy = true; state.revisionKey = 'room1:p1:2'; key('Enter')
    expect(callbacks.discard).toHaveBeenCalledTimes(1)
    state.busy = false; key('Enter')
    expect(callbacks.discard).toHaveBeenCalledTimes(2)
    expect(callbacks.discard).toHaveBeenLastCalledWith(hand[1])
    state.status = 'REACTION'; state.canDiscard = false; state.actions = [pass]; key(' ')
    expect(callbacks.pass).not.toHaveBeenCalled()
    state.revisionKey = 'room1:p1:3'; key(' '); key(' ')
    expect(callbacks.pass).toHaveBeenCalledExactlyOnceWith(pass)
  })

  it('allows a same-revision retry after busy settles without weakening synchronous duplicate protection', () => {
    const { state, callbacks } = harness(context({ selectedId: drawn.id }))
    key('Enter'); key('Enter')
    expect(callbacks.discard).toHaveBeenCalledExactlyOnceWith(drawn)
    state.busy = true; key('Enter')
    expect(callbacks.discard).toHaveBeenCalledTimes(1)
    state.busy = false; key('Enter'); key('Enter')
    expect(callbacks.discard).toHaveBeenCalledTimes(2)
    expect(callbacks.discard).toHaveBeenLastCalledWith(drawn)
    expect(state.revisionKey).toBe('room1:p1:1')
  })

  it('revalidates current legal actions and modal/busy guards before a failed PASS retry', () => {
    const { state, callbacks } = harness(context({ status: 'REACTION', canDiscard: false, actions: [pass] }))
    key(' '); state.busy = true; key(' '); state.busy = false
    state.actions = [{ type: 'CHI', label: '吃', tileIds: [hand[0].id, hand[1].id] }]; key(' ')
    state.actions = [pass]; state.overlayOpen = true; key(' ')
    state.overlayOpen = false; state.expired = true; key(' ')
    expect(callbacks.pass).toHaveBeenCalledTimes(1)
    state.expired = false; key(' '); key(' ')
    expect(callbacks.pass).toHaveBeenCalledTimes(2)
    expect(callbacks.pass).toHaveBeenLastCalledWith(pass)
  })

  it.each(['<button class="ym-hand-tile"><span>刚才选中的手牌</span></button>',
    '<button class="ym-call-option"><span>吃 123 条</span></button>'])('Space takes PASS instead of activating a still-focused game button %s', html => {
    const chi: Action = { type: 'CHI', label: '吃', tileIds: [hand[0].id, hand[1].id] }
    const { state, callbacks } = harness(context({ status: 'REACTION', canDiscard: false, actions: [chi, pass] }))
    const control = element(html), click = vi.fn()
    control.addEventListener('click', click); control.focus()
    const event = key(' ', {}, control.firstElementChild!)
    // Simulate the browser's default button activation only if keydown was not consumed.
    if (!event.defaultPrevented) control.click()
    expect(event.defaultPrevented).toBe(true)
    expect(callbacks.pass).toHaveBeenCalledExactlyOnceWith(pass)
    expect(callbacks.select).not.toHaveBeenCalled(); expect(callbacks.discard).not.toHaveBeenCalled()
    expect(click).not.toHaveBeenCalled()
    expect(key(' ', { repeat: true }, control).defaultPrevented).toBe(true)
    state.busy = true
    expect(key(' ', {}, control).defaultPrevented).toBe(true)
    expect(callbacks.pass).toHaveBeenCalledTimes(1)
  })

  it('allows Enter on a hand tile after A/D selection while preventing native click double activation', () => {
    const { callbacks } = harness()
    const button = element('<button class="ym-hand-tile"><span>手牌</span></button>'); button.focus()
    key('a', {}, button.firstElementChild!)
    expect(key('Enter', {}, button).defaultPrevented).toBe(true)
    expect(callbacks.discard).toHaveBeenCalledExactlyOnceWith(drawn)
    expect(key('Enter', { repeat: true }, button).defaultPrevented).toBe(true)
    expect(callbacks.discard).toHaveBeenCalledTimes(1)
    expect(key('Enter', { isComposing: true }, button).defaultPrevented).toBe(false)
    expect(key('Enter', { ctrlKey: true }, button).defaultPrevented).toBe(false)
  })
})

describe('keyboard boundaries, modal isolation and disposal', () => {
  it.each(['enabled', 'canDiscard', 'busy', 'expired', 'trustee', 'overlayOpen'] as const)('does not select/discard when blocked by %s', reason => {
    const { callbacks } = harness(context({ selectedId: drawn.id, [reason]: reason === 'enabled' || reason === 'canDiscard' ? false : true }))
    key('a'); key('d'); key('Enter'); key(' ')
    expect(callbacks.select).not.toHaveBeenCalled(); expect(callbacks.discard).not.toHaveBeenCalled()
  })

  it.each(['enabled', 'busy', 'expired', 'trustee', 'overlayOpen'] as const)('does not pass when blocked by %s', reason => {
    const { callbacks } = harness(context({ status: 'REACTION', canDiscard: false, actions: [pass],
      [reason]: reason !== 'enabled' }))
    key(' '); expect(callbacks.pass).not.toHaveBeenCalled()
  })

  it.each([{ repeat: true }, { isComposing: true }, { keyCode: 229 }, { ctrlKey: true }, { altKey: true }, { metaKey: true }])('ignores held/IME/modified keys %j', init => {
    const { state, callbacks } = harness(context({ selectedId: drawn.id }))
    key('a', init); key('d', init); key('Enter', init); key(' ', init)
    state.status = 'REACTION'; state.actions = [pass]; key(' ', init)
    expect(callbacks.select).not.toHaveBeenCalled(); expect(callbacks.discard).not.toHaveBeenCalled(); expect(callbacks.pass).not.toHaveBeenCalled()
  })

  it.each(['<input>', '<textarea></textarea>', '<select><option>选项</option></select>',
    '<div contenteditable="true"><span tabindex="0">编辑</span></div>', '<div contenteditable="plaintext-only"><span>编辑</span></div>'])('leaves editing keys alone in %s', html => {
    const { state, callbacks } = harness(context({ selectedId: drawn.id }))
    const outer = element(html), target = outer.querySelector('span') || outer
    target.focus(); key('a', {}, target); key('Enter', {}, target)
    expect(key(' ', {}, target).defaultPrevented).toBe(false)
    state.status = 'REACTION'; state.actions = [pass]; key(' ', {}, target)
    expect(callbacks.select).not.toHaveBeenCalled(); expect(callbacks.discard).not.toHaveBeenCalled(); expect(callbacks.pass).not.toHaveBeenCalled()
  })

  it('also protects an active input when the event is dispatched at document', () => {
    const { callbacks } = harness(context({ selectedId: drawn.id }))
    element('<input>').focus(); key('d'); key('Enter'); key(' ')
    expect(callbacks.select).not.toHaveBeenCalled(); expect(callbacks.discard).not.toHaveBeenCalled()
  })

  it.each(['<button>正常按钮</button>', '<details><summary>展开</summary></details>', '<a href="#">导航</a>',
    '<span tabindex="0" role="button">按钮</span>'])('keeps native Enter but uses legal reaction PASS for Space on an interactive control %s', html => {
    const { state, callbacks } = harness(context({ selectedId: drawn.id }))
    const outer = element(html), control = outer.querySelector('summary') || outer; control.focus()
    expect(key('Enter', {}, control).defaultPrevented).toBe(false)
    state.status = 'REACTION'; state.actions = [pass]
    expect(key(' ', {}, control).defaultPrevented).toBe(true)
    expect(callbacks.discard).not.toHaveBeenCalled(); expect(callbacks.pass).toHaveBeenCalledExactlyOnceWith(pass)
  })

  it.each(['<div class="ym-overlay"></div>', '<section role="dialog" aria-modal="true"></section>'])('blocks game shortcuts whenever an actual modal is open %s', html => {
    const { state, callbacks } = harness(context({ selectedId: drawn.id }))
    const modal = element(html)
    key('a'); key('Enter'); key(' '); state.status = 'REACTION'; state.actions = [pass]; key(' ')
    expect(callbacks.select).not.toHaveBeenCalled(); expect(callbacks.discard).not.toHaveBeenCalled(); expect(callbacks.pass).not.toHaveBeenCalled()
    modal.remove(); key(' '); expect(callbacks.pass).toHaveBeenCalledExactlyOnceWith(pass)
  })

  it.each(['<div class="ym-overlay" hidden></div>', '<div style="display:none"><section aria-modal="true"></section></div>',
    '<dialog aria-modal="true"></dialog>'])('does not treat hidden/closed modal scaffolding as an open overlay %s', html => {
    const { callbacks } = harness(); element(html); key('d')
    expect(callbacks.select).toHaveBeenCalledExactlyOnceWith(hand[0])
  })

  it('ignores events already consumed by a dialog and removes its document listener on unmount', () => {
    const add = vi.spyOn(document, 'addEventListener'), remove = vi.spyOn(document, 'removeEventListener')
    const { wrapper, callbacks } = harness(context({ selectedId: drawn.id }))
    const registered = add.mock.calls.find(([type]) => type === 'keydown')
    expect(registered).toBeDefined()
    for (const shortcut of ['Enter', ' ']) {
      const event = new KeyboardEvent('keydown', { key: shortcut, bubbles: true, cancelable: true }); event.preventDefault(); document.dispatchEvent(event)
    }
    expect(callbacks.discard).not.toHaveBeenCalled()
    wrapper.unmount()
    expect(remove).toHaveBeenCalledWith('keydown', registered![1])
    key('a'); key('Enter'); key(' '); expect(callbacks.select).not.toHaveBeenCalled(); expect(callbacks.discard).not.toHaveBeenCalled()
  })
})

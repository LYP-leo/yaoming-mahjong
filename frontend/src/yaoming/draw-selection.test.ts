import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import TableView from './TableView.vue'
import { player, room } from './testFixtures'
import type { Action, Player, RoomView, Tile } from './types'

const hand: Tile[] = Array.from({ length: 13 }, (_, index) => ({ id: `held-${index}`, suit: 'BAMBOO', rank: index % 9 + 1, label: `${index % 9 + 1}条` }))
const drawn: Tile = { ...hand[5], id: 'drawn-original' }
const discard = (tile: Tile): Action => ({ type: 'DISCARD', label: `打出 ${tile.label}`, tileIds: [tile.id] })
const wrappers: VueWrapper[] = [], hosts: HTMLElement[] = []
function view(overrides: Partial<RoomView> = {}, own: Partial<Player> = {}): RoomView {
  const me = { ...player(), hand: [...hand, drawn], handSize: 14, drawnTileId: drawn.id, ...own }
  return room({ players: [me, player('p2', 1), player('p3', 2)], actions: me.hand.map(discard), ...overrides })
}
function table(value = view(), props: { busy?: boolean; readonly?: boolean } = {}) {
  const host = document.createElement('div'); document.body.append(host); hosts.push(host)
  const wrapper = mount(TableView, { props: { room: value, busy: false, ...props }, attachTo: host })
  wrappers.push(wrapper); return wrapper
}
function selection(wrapper: VueWrapper) {
  return wrapper.findAll('.ym-hand-tile-selected').map(button => button.attributes('data-hand-tile-id'))
}
async function key(value: string, target: EventTarget = document, init: KeyboardEventInit = {}) {
  const event = new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...init })
  target.dispatchEvent(event); await nextTick(); return event
}
beforeEach(() => { localStorage.clear(); vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-11T12:00:00Z')) })
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount()); hosts.splice(0).forEach(host => host.remove())
  vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks()
})

describe('default selection of the authoritative drawn entity', () => {
  it.each([false, true])('selects the new physical draw on mount without submission; quickDiscard=%s', quick => {
    localStorage.setItem('yaoming.quickDiscard', String(quick))
    const wrapper = table()
    expect(selection(wrapper)).toEqual([drawn.id])
    expect(wrapper.get('.ym-draw-slot .ym-hand-tile').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get(`[data-hand-tile-id="${hand[5].id}"]`).attributes('aria-pressed')).toBe('false')
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('selects a normal draw arriving after NEED_DRAW without clicking or submitting', async () => {
    const wrapper = table(view({ status: 'NEED_DRAW', actions: [{ type: 'DRAW', label: '摸牌', tileIds: [] }] }, { hand, handSize: 13, drawnTileId: null }))
    expect(selection(wrapper)).toEqual([])
    await wrapper.setProps({ room: view({ version: 2, status: 'NEED_DISCARD' }) })
    expect(selection(wrapper)).toEqual([drawn.id])
    await vi.advanceTimersByTimeAsync(1000)
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('selects the replacement draw after a kong by entity ID even when another held tile has the same face', async () => {
    const wrapper = table(), replacement: Tile = { ...drawn, id: 'replacement-after-kong' }
    const kongTiles = [drawn, ...[1, 2, 3].map(index => ({ ...drawn, id: `kong-${index}` }))]
    const kong: Player['melds'][number] = { type: 'KONG', tiles: kongTiles, fromSeat: 0, claimedTileId: '', concealed: true }
    const remaining = hand.slice(0, 10)
    await wrapper.setProps({ room: view({ version: 2, status: 'NEED_DRAW', actions: [{ type: 'DRAW', label: '补牌', tileIds: [] }] },
      { hand: remaining, handSize: 10, drawnTileId: null, melds: [kong] }) })
    expect(selection(wrapper)).toEqual([])
    await wrapper.setProps({ room: view({ version: 3 }, { hand: [...remaining, replacement], handSize: 11, drawnTileId: replacement.id, melds: [kong] }) })
    expect(selection(wrapper)).toEqual([replacement.id])
    expect(wrapper.get(`[data-hand-tile-id="${hand[5].id}"]`).attributes('aria-pressed')).toBe('false')
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it.each(['mouse', 'A', 'D'] as const)('retains a %s choice across message/version-only heartbeats', async method => {
    const initial = view(), wrapper = table(initial)
    if (method === 'mouse') await wrapper.get(`[data-hand-tile-id="${hand[0].id}"]`).trigger('click')
    else await key(method)
    const chosen = selection(wrapper)
    expect(chosen).toHaveLength(1); expect(chosen[0]).not.toBe(drawn.id)
    const element = wrapper.get('.ym-hand-tile-selected').element
    await wrapper.setProps({ room: { ...initial, version: 2, message: '另一位玩家连接状态更新', events: [{ sequence: 1, text: '在线' }],
      players: initial.players.map(owner => ({ ...owner, hand: [...owner.hand], online: !owner.online })) } })
    expect(selection(wrapper)).toEqual(chosen)
    expect(wrapper.get('.ym-hand-tile-selected').element).toBe(element)
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('allows visual default selection while the action response is busy but cannot submit until it settles', async () => {
    const wrapper = table(view(), { busy: true })
    expect(selection(wrapper)).toEqual([drawn.id])
    await key(' '); await key('Enter')
    expect(wrapper.emitted('action')).toBeUndefined()
    await wrapper.setProps({ busy: false })
    expect(selection(wrapper)).toEqual([drawn.id])
    await key('Enter')
    expect(wrapper.emitted('action')).toEqual([[discard(drawn)]])
  })
})

describe('default selection eligibility boundaries', () => {
  const excluded: { label: string; change?: Partial<RoomView>; own?: Partial<Player>; readonly?: boolean }[] = [
    { label: 'another player turn', change: { currentSeat: 1 } },
    { label: 'readonly replay', readonly: true },
    { label: 'trustee', own: { trustee: true } },
    { label: 'expired discard', change: { deadlineAt: '2026-09-11T11:59:59Z', deadlineKind: 'DISCARD' } },
    { label: 'no drawn ID after a claim', own: { hand, handSize: 13, drawnTileId: null } },
    { label: 'missing drawn ID', own: { drawnTileId: undefined } },
    { label: 'drawn entity absent from hand', own: { drawnTileId: 'not-in-hand' } },
    { label: 'draw excluded from legal discards', change: { actions: hand.map(discard) } },
    { label: 'only non-discard actions', change: { actions: [{ type: 'CHI', label: '吃', tileIds: [drawn.id] }, { type: 'WIN', label: '自摸', tileIds: [] }] } },
    { label: 'malformed multi-entity discard', change: { actions: [{ type: 'DISCARD', label: '错误动作', tileIds: [drawn.id, hand[0].id] }] } },
    ...['WAITING', 'NEED_DRAW', 'REACTION', 'HAND_END', 'MATCH_END'].map(status => ({ label: `${status} with stale drawn metadata`, change: { status } })),
  ]
  it.each(excluded)('does not invent a default selection for $label', ({ change, own, readonly }) => {
    const wrapper = table(view(change, own), { readonly })
    expect(selection(wrapper)).toEqual([])
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it.each(['readonly', 'trustee', 'expired'] as const)('removes a previously selected draw when %s blocks the existing turn', async reason => {
    const initial = view(), wrapper = table(initial)
    expect(selection(wrapper)).toEqual([drawn.id])
    if (reason === 'readonly') await wrapper.setProps({ readonly: true })
    else await wrapper.setProps({ room: reason === 'trustee' ? view({}, { trustee: true })
      : view({ deadlineAt: '2026-09-11T11:59:59Z', deadlineKind: 'DISCARD' }) })
    expect(selection(wrapper)).toEqual([])
    await key(' '); await key('Enter')
    expect(wrapper.emitted('action')).toBeUndefined()
  })
})

describe('explicit confirmation of the default draw', () => {
  it.each([' ', 'Enter'])('confirms the exact default draw once with %j and shares the Space/Enter lock', async confirm => {
    const initial = view(), wrapper = table(initial), original = JSON.stringify(initial)
    const first = await key(confirm)
    expect(first.defaultPrevented).toBe(true)
    await key(confirm === ' ' ? 'Enter' : ' ')
    await key(confirm, document, { repeat: true })
    expect(wrapper.emitted('action')).toEqual([[discard(drawn)]])
    expect(JSON.stringify(initial)).toBe(original)
  })

  it.each([' ', 'Enter'])('confirms current selection instead of activating a differently focused tile with %j', async confirm => {
    const wrapper = table(), oldFocus = wrapper.get<HTMLButtonElement>(`[data-hand-tile-id="${hand[0].id}"]`).element
    const nativeClick = vi.fn(); oldFocus.addEventListener('click', nativeClick); oldFocus.focus()
    expect(selection(wrapper)).toEqual([drawn.id])
    const event = await key(confirm, oldFocus)
    if (!event.defaultPrevented) oldFocus.click()
    expect(event.defaultPrevented).toBe(true); expect(nativeClick).not.toHaveBeenCalled()
    expect(wrapper.emitted('action')).toEqual([[discard(drawn)]])
  })

  it('Space still sends PASS in a reaction phase without using stale drawn metadata', async () => {
    const pass: Action = { type: 'PASS', label: '过', tileIds: [] }
    const wrapper = table(view({ status: 'REACTION', actions: [pass] }))
    expect(selection(wrapper)).toEqual([])
    await key(' ')
    expect(wrapper.emitted('action')).toEqual([[pass]])
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { AUTO_DRAW_DELAY_MS, useAutoDraw, type AutoDrawContext } from './useAutoDraw'
import { usePlayPreferences } from './usePlayPreferences'
import SettingsDialog from './SettingsDialog.vue'
import YaomingApp from './YaomingApp.vue'
import TableView from './TableView.vue'
import { useYaomingStore, yaomingApi } from './store'
import { room, result } from './testFixtures'
import type { Action, RoomView } from './types'

const draw: Action = { type: 'DRAW', label: '摸牌', tileIds: [] }
const identity = { roomId: 'room1', playerId: 'p1', token: 'draw-private' }
const drawRoom = (overrides: Partial<RoomView> = {}) => room({ status: 'NEED_DRAW', actions: [draw, ...room().actions.filter(action => action.type === 'LEAVE')], ...overrides })
function context(): AutoDrawContext { return { identity: { ...identity }, room: drawRoom(), enabled: true, paused: false, connected: true, busy: false, syncing: false } }
function harness(initial = context(), send = vi.fn().mockResolvedValue(true)) {
  const state = reactive(initial)
  const component = defineComponent({ setup() { const { notice } = useAutoDraw(() => state, send); return () => h('p', notice.value) } })
  return { state, send, wrapper: mount(component) }
}
beforeEach(() => { vi.restoreAllMocks(); vi.useFakeTimers(); localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia()) })
afterEach(() => { vi.useRealTimers() })

describe('automatic draw permission and cancellation', () => {
  it('requests exactly the legal DRAW action once and never submits another kind of gameplay', async () => {
    const { wrapper, state, send } = harness()
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS)
    expect(send).toHaveBeenCalledExactlyOnceWith(draw)
    state.room = drawRoom({ version: 5, serverTime: '2026-09-08T01:00:00Z' })
    await vi.advanceTimersByTimeAsync(10000)
    expect(send).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it.each(['disabled', 'otherTurn', 'wrongIdentity', 'wrongRoom', 'missingIdentity', 'noDraw', 'badDrawIds', 'trustee', 'bot', 'busy', 'syncing', 'offline', 'paused', 'discardPhase'] as const)('does not draw when %s', async reason => {
    const initial = context()
    if (reason === 'disabled') initial.enabled = false
    if (reason === 'otherTurn') initial.room!.currentSeat = 1
    if (reason === 'wrongIdentity') initial.identity!.playerId = 'p2'
    if (reason === 'wrongRoom') initial.identity!.roomId = 'other-room'
    if (reason === 'missingIdentity') initial.identity = null
    if (reason === 'noDraw') initial.room!.actions = []
    if (reason === 'badDrawIds') initial.room!.actions = [{ ...draw, tileIds: ['invented'] }]
    if (reason === 'trustee') initial.room!.players[0].trustee = true
    if (reason === 'bot') initial.room!.players[0].bot = true
    if (reason === 'busy') initial.busy = true
    if (reason === 'syncing') initial.syncing = true
    if (reason === 'offline') initial.connected = false
    if (reason === 'paused') initial.paused = true
    if (reason === 'discardPhase') initial.room!.status = 'NEED_DISCARD'
    const { wrapper, send } = harness(initial)
    await vi.advanceTimersByTimeAsync(10000); expect(send).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it.each(['pause', 'identity', 'room', 'round', 'version', 'manualBusy', 'disabled'] as const)('cancels a scheduled stale task when %s changes', async reason => {
    const { wrapper, state, send } = harness()
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS - 10)
    if (reason === 'pause') state.paused = true
    if (reason === 'identity') state.identity!.token = 'replacement-private'
    if (reason === 'room') state.room!.id = 'another-room'
    if (reason === 'round') state.room!.round = 2
    if (reason === 'version') state.room!.version = 2
    if (reason === 'manualBusy') state.busy = true
    if (reason === 'disabled') state.enabled = false
    await vi.advanceTimersByTimeAsync(10); expect(send).not.toHaveBeenCalled()
    wrapper.unmount(); await vi.advanceTimersByTimeAsync(10000); expect(send).not.toHaveBeenCalled()
  })
  it('ignores equal-version poll timestamps without delaying an already scheduled current action', async () => {
    const { wrapper, state, send } = harness()
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS - 10)
    state.room = drawRoom({ serverTime: '2026-09-08T01:00:00Z', clientReceivedAt: Date.now() })
    await vi.advanceTimersByTimeAsync(10); expect(send).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it('resumes a not-yet-attempted turn after an overlay closes', async () => {
    const { wrapper, state, send } = harness({ ...context(), paused: true })
    await vi.advanceTimersByTimeAsync(1000); expect(send).not.toHaveBeenCalled()
    state.paused = false; await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS)
    expect(send).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it('does not loop on failed requests even when refresh increases the version or overlays reopen', async () => {
    const send = vi.fn().mockResolvedValue(false), { wrapper, state } = harness(context(), send)
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS)
    expect(wrapper.text()).toContain('请点击「摸牌」手动重试')
    for (let version = 2; version < 10; version++) { state.room = drawRoom({ version }); state.paused = true; state.paused = false; await vi.advanceTimersByTimeAsync(1000) }
    expect(send).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it('contains a thrown request error and still allows a genuinely new physical turn', async () => {
    const send = vi.fn().mockRejectedValueOnce(new Error('Network Error')).mockResolvedValue(true)
    const { wrapper, state } = harness(context(), send)
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS); expect(wrapper.text()).toContain('自动摸牌未成功')
    state.room = room({ status: 'NEED_DISCARD', version: 2 })
    state.room = drawRoom({ wallCount: 65, version: 8 })
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS); expect(send).toHaveBeenCalledTimes(2); wrapper.unmount()
  })
  it('rechecks the server-calibrated deadline at submission, including cached snapshots with clock skew', async () => {
    vi.setSystemTime(new Date('2026-09-08T02:00:00Z'))
    const initial = context()
    initial.room = drawRoom({ serverTime: '2026-09-08T01:00:00Z', clientReceivedAt: Date.now(), deadlineAt: '2026-09-08T01:00:00.100Z', deadlineKind: 'DRAW' })
    const { wrapper, send } = harness(initial)
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS); expect(send).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('clears pending work and does not publish failures after unmount', async () => {
    const { wrapper, send } = harness()
    wrapper.unmount(); await vi.advanceTimersByTimeAsync(10000); expect(send).not.toHaveBeenCalled()
  })
})

describe('local play preferences and settings', () => {
  it('defaults auto draw on and quick discard off, persists changes and restores them after remount', async () => {
    const first = mount(SettingsDialog, { props: { playing: false } })
    expect((first.get('[aria-label="自动摸牌"]').element as HTMLInputElement).checked).toBe(true)
    expect((first.get('[aria-label="快捷出牌"]').element as HTMLInputElement).checked).toBe(false)
    await first.get('[aria-label="自动摸牌"]').setValue(false)
    await first.get('[aria-label="快捷出牌"]').setValue(true)
    expect(localStorage.getItem('yaoming.autoDraw')).toBe('false'); expect(localStorage.getItem('yaoming.quickDiscard')).toBe('true')
    first.unmount()
    const second = mount(SettingsDialog, { props: { playing: false } })
    expect((second.get('[aria-label="自动摸牌"]').element as HTMLInputElement).checked).toBe(false)
    expect((second.get('[aria-label="快捷出牌"]').element as HTMLInputElement).checked).toBe(true); second.unmount()
  })
  it('synchronizes open table quick discard with settings without a remount', async () => {
    const table = mount(TableView, { props: { room: room(), busy: false } })
    const settings = mount(SettingsDialog, { props: { playing: true } })
    await settings.get('[aria-label="快捷出牌"]').setValue(true)
    expect((table.get('.ym-play-settings input').element as HTMLInputElement).checked).toBe(true)
    await table.get('.ym-hand-tile').trigger('click')
    expect(table.emitted('action')).toHaveLength(1)
    table.unmount(); settings.unmount()
  })
  it('responds to other-tab preference changes and removes event listeners after unmount', async () => {
    const component = defineComponent({ setup() { const { autoDraw } = usePlayPreferences(); return () => h('p', String(autoDraw.value)) } })
    const remove = vi.spyOn(window, 'removeEventListener'), wrapper = mount(component)
    localStorage.setItem('yaoming.autoDraw', 'false'); window.dispatchEvent(new StorageEvent('storage', { key: 'yaoming.autoDraw' }))
    await flushPromises(); expect(wrapper.text()).toBe('false')
    wrapper.unmount(); expect(remove.mock.calls.filter(call => call[0] === 'storage')).toHaveLength(2)
  })
  it('tolerates unavailable persistent storage while applying the setting to this session', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    const wrapper = mount(SettingsDialog, { props: { playing: true } })
    await wrapper.get('[aria-label="自动摸牌"]').setValue(false)
    expect((wrapper.get('[aria-label="自动摸牌"]').element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.text()).toContain('倒计时仍会继续'); wrapper.unmount()
  })
  it('supports Escape and traps tab focus inside the settings dialog', async () => {
    const wrapper = mount(SettingsDialog, { props: { playing: false }, attachTo: document.body })
    const dialog = wrapper.get('[role="dialog"]')
    expect(document.activeElement).toBe(dialog.element)
    await dialog.trigger('keydown', { key: 'Tab', shiftKey: true })
    expect((document.activeElement as HTMLElement).textContent).toContain('完成设置')
    await wrapper.get('.ym-submit').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement?.getAttribute('aria-label')).toBe('关闭设置')
    await dialog.trigger('keydown', { key: 'Escape' }); expect(wrapper.emitted('close')).toHaveLength(1); wrapper.unmount()
  })
})

describe('application auto draw integration', () => {
  // Automatic listening runs beside automatic draw; keep its read-only requests
  // inside this fixture instead of reaching a real server or returning replay data.
  beforeEach(() => {
    vi.spyOn(yaomingApi, 'get').mockImplementation(async path => {
      const store = useYaomingStore()
      if (path?.endsWith('/hints')) return { data: { roomId: store.room?.id || identity.roomId,
        playerId: store.identity?.playerId || identity.playerId, version: store.room?.version || 1,
        analysis: { mode: 'UNAVAILABLE', waits: [], discards: [], note: '' } } }
      if (path === '/replays') return { data: { roomId: 'room1', roomName: '记录', hands: [], note: '' } }
      throw new Error(`Unexpected GET in auto-draw fixture: ${path}`)
    })
  })
  function app(view: RoomView | null = drawRoom()) {
    const store = useYaomingStore(); store.identity = view ? { ...identity } : null; store.room = view; store.connected = true
    vi.spyOn(store, 'start').mockResolvedValue(); vi.spyOn(store, 'stop').mockImplementation(() => {})
    vi.spyOn(store, 'loadRules').mockResolvedValue()
    const act = vi.spyOn(store, 'act').mockResolvedValue(true)
    return { store, act, wrapper: mount(YaomingApp) }
  }
  it('makes settings available from the lobby and pauses draw while settings are open on the table', async () => {
    const { wrapper, store, act } = app(null)
    await wrapper.findAll('.ym-nav-links button').find(button => button.text() === '设置')!.trigger('click')
    expect(wrapper.findComponent(SettingsDialog).exists()).toBe(true)
    store.identity = { ...identity }; store.room = drawRoom()
    await vi.advanceTimersByTimeAsync(1000); expect(act).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="关闭设置"]').trigger('click')
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS); expect(act).toHaveBeenCalledExactlyOnceWith(draw); wrapper.unmount()
  })
  it.each(['规则手册', '牌谱复盘', '离开房间', '暂离 · 返回大厅'])('pauses auto actions while %s hides or blocks the live table', async label => {
    const { wrapper, act } = app()
    await wrapper.findAll('button').find(button => button.text() === label)!.trigger('click')
    await vi.advanceTimersByTimeAsync(1000); expect(act).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('does not auto-draw underneath a visible settlement even if a stale server snapshot includes DRAW', async () => {
    const { wrapper, act } = app(drawRoom({ result: result() }))
    await vi.advanceTimersByTimeAsync(1000); expect(act).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('uses the existing serialized action path so a manual draw wins a timer race without a second write', async () => {
    const store = useYaomingStore(); store.identity = { ...identity }; store.room = drawRoom(); store.connected = true
    vi.spyOn(store, 'start').mockResolvedValue(); vi.spyOn(store, 'stop').mockImplementation(() => {})
    let deliver!: (response: unknown) => void
    const post = vi.spyOn(yaomingApi, 'post').mockReturnValue(new Promise(resolve => { deliver = resolve }) as never)
    const wrapper = mount(YaomingApp)
    await wrapper.findAll('.ym-action-bar button').find(button => button.text() === '摸牌')!.trigger('click')
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS)
    expect(post).toHaveBeenCalledTimes(1)
    deliver({ data: room({ version: 2, status: 'NEED_DISCARD' }) }); await flushPromises()
    await vi.advanceTimersByTimeAsync(1000); expect(post).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
})

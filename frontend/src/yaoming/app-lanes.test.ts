import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import YaomingApp from './YaomingApp.vue'
import HintsPanel from './HintsPanel.vue'
import RoomSidebar from './RoomSidebar.vue'
import TableView from './TableView.vue'
import RulesPage from './RulesPage.vue'
import ReplayPage from './ReplayPage.vue'
import { AUTO_DRAW_DELAY_MS } from './useAutoDraw'
import { useYaomingStore, yaomingApi } from './store'
import { room, tile } from './testFixtures'
import type { Action, RoomView } from './types'

const identity = { roomId: 'room1', playerId: 'p1', token: 'app-lanes-local-test-seat' }
const draw: Action = { type: 'DRAW', label: '摸牌', tileIds: [] }
const wrappers: VueWrapper[] = []

function drawRoom(): RoomView {
  const view = room({ status: 'NEED_DRAW', actions: [draw] })
  view.players[0].discards = Array.from({ length: 17 }, (_, i) => ({ ...tile,
    id: `lane-river-${i}`, suit: i < 9 ? 'BAMBOO' : 'DOTS', rank: i % 9 + 1, label: `弃牌${i + 1}` }))
  return view
}

function app(view = drawRoom()) {
  const store = useYaomingStore()
  store.identity = { ...identity }; store.room = view; store.connected = true
  vi.spyOn(store, 'start').mockResolvedValue()
  vi.spyOn(store, 'stop').mockImplementation(() => {})
  const loadRules = vi.spyOn(store, 'loadRules').mockResolvedValue()
  const act = vi.spyOn(store, 'act').mockResolvedValue(true)
  const wrapper = mount(YaomingApp)
  wrappers.push(wrapper)
  return { wrapper, store, act, loadRules }
}

async function openOwnRiver(wrapper: VueWrapper) {
  await wrapper.get('.ym-lane-self .ym-lane-river-more').trigger('click')
  expect(wrapper.get('.ym-river-dialog').findAll('.ym-full-river li')).toHaveLength(17)
}

beforeEach(() => {
  vi.restoreAllMocks(); vi.useFakeTimers()
  localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
  vi.spyOn(yaomingApi, 'get').mockImplementation(async path => {
    const store = useYaomingStore()
    if (path?.endsWith('/hints')) return { data: { roomId: store.room?.id || identity.roomId,
      playerId: store.identity?.playerId || identity.playerId, version: store.room?.version || 1,
      analysis: { mode: 'WAIT', waits: [], discards: [], note: '' } } }
    if (path === '/replays') return { data: { roomId: identity.roomId, roomName: '本地牌谱', hands: [], note: '' } }
    throw new Error(`Unexpected GET in app-lanes fixture: ${path}`)
  })
})
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals()
})

describe('player-lanes application composition', () => {
  it('mounts listening exactly once under the hand-panel slot, never in the sidebar', async () => {
    const { wrapper, store, act } = app(room())
    expect(wrapper.findAllComponents(HintsPanel)).toHaveLength(1)
    expect(wrapper.findComponent(TableView).findAllComponents(HintsPanel)).toHaveLength(1)
    expect(wrapper.findComponent(RoomSidebar).findComponent(HintsPanel).exists()).toBe(false)
    const hints = wrapper.get('.ym-hand-hints .ym-hints').element
    expect(wrapper.findAll('.ym-sidebar .ym-hints')).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(200)
    store.room = { ...store.room!, version: 2, message: '另一位玩家已重新连接' }
    await nextTick()
    expect(wrapper.get('.ym-hand-hints .ym-hints').element).toBe(hints)
    expect(wrapper.findAllComponents(HintsPanel)).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(200)
    expect(act).not.toHaveBeenCalled()
  })

  it('opens rules from the actual sidebar and returns to a single live table', async () => {
    const { wrapper, loadRules } = app(room())
    await wrapper.findComponent(RoomSidebar).get('.ym-sidebar-rules').trigger('click')
    expect(loadRules).toHaveBeenCalledOnce()
    expect(wrapper.findComponent(RulesPage).exists()).toBe(true)
    expect(wrapper.findComponent(TableView).exists()).toBe(false)
    await wrapper.get('.ym-page-heading > button').trigger('click')
    expect(wrapper.findComponent(RulesPage).exists()).toBe(false)
    expect(wrapper.findAllComponents(TableView)).toHaveLength(1)
    expect(wrapper.findAllComponents(HintsPanel)).toHaveLength(1)
  })

  it('opens replay from the actual sidebar with the current participant identity', async () => {
    const { wrapper } = app(room())
    await wrapper.findComponent(RoomSidebar).get('.ym-replay-entry button').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(ReplayPage).exists()).toBe(true)
    expect(wrapper.findComponent(ReplayPage).props('identity')).toEqual(identity)
    expect(yaomingApi.get).toHaveBeenCalledWith('/replays', {
      params: { roomId: identity.roomId, playerId: identity.playerId }, headers: { 'X-Resume-Token': identity.token },
    })
    await wrapper.get('.ym-replay-heading > button').trigger('click')
    expect(wrapper.findComponent(ReplayPage).exists()).toBe(false)
    expect(wrapper.findAllComponents(TableView)).toHaveLength(1)
  })

  it('routes explicit room-code and recovery-code copies through the existing app clipboard feedback', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const { wrapper } = app(room({ status: 'WAITING', actions: [] }))
    const sidebar = wrapper.findComponent(RoomSidebar)
    expect(sidebar.text()).not.toContain(identity.token)
    await sidebar.get('.ym-invite-card button').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledExactlyOnceWith(identity.roomId)
    expect(wrapper.get('.ym-notice').text()).toBe('房间号已复制')
    const recovery = sidebar.get('details.ym-recovery')
    ;(recovery.element as HTMLDetailsElement).open = true
    await recovery.trigger('toggle')
    await sidebar.get('.ym-sidebar-recovery-body button').trigger('click')
    await flushPromises()
    expect(writeText.mock.calls).toEqual([[identity.roomId], [identity.token]])
    expect(wrapper.get('.ym-notice').text()).toBe('恢复码已复制')
  })
})

describe('full-river inspection and automatic draw integration', () => {
  it('cancels a queued draw while inspecting, keeps the dialog on unrelated version updates, and resumes once', async () => {
    const { wrapper, store, act } = app()
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS - 10)
    await openOwnRiver(wrapper)
    const dialog = wrapper.get('.ym-river-dialog').element
    await vi.advanceTimersByTimeAsync(1000)
    expect(act).not.toHaveBeenCalled()
    store.room = { ...store.room!, version: 9, message: '玩家2 恢复连接', clientReceivedAt: Date.now() }
    await nextTick()
    expect(wrapper.get('.ym-river-dialog').element).toBe(dialog)
    await vi.advanceTimersByTimeAsync(1000)
    expect(act).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="关闭完整牌河"]').trigger('click')
    expect(wrapper.find('.ym-river-dialog').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS - 1)
    expect(act).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(act).toHaveBeenCalledExactlyOnceWith(draw)
    await openOwnRiver(wrapper)
    await wrapper.get('.ym-river-dialog').trigger('keydown', { key: 'Escape' })
    store.room = { ...store.room!, version: 10, message: '普通轮询' }
    await vi.advanceTimersByTimeAsync(2000)
    expect(act).toHaveBeenCalledTimes(1)
  })

  it('does not revive a stale DRAW when the server has changed phase before the river closes', async () => {
    const { wrapper, store, act } = app()
    await openOwnRiver(wrapper)
    store.room = { ...store.room!, status: 'NEED_DISCARD', version: 2,
      actions: [{ type: 'DISCARD', label: '出牌', tileIds: [tile.id] }] }
    await nextTick()
    expect(wrapper.find('.ym-river-dialog').exists()).toBe(true)
    await wrapper.get('[aria-label="关闭完整牌河"]').trigger('click')
    await vi.advanceTimersByTimeAsync(1000)
    expect(act).not.toHaveBeenCalled()
  })

  it('clears the overlay pause when rules navigation unmounts the table and resumes after returning', async () => {
    const { wrapper, act } = app()
    await openOwnRiver(wrapper)
    await wrapper.findComponent(RoomSidebar).get('.ym-sidebar-rules').trigger('click')
    expect(wrapper.find('.ym-river-dialog').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(1000)
    expect(act).not.toHaveBeenCalled()
    await wrapper.get('.ym-page-heading > button').trigger('click')
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS)
    expect(act).toHaveBeenCalledExactlyOnceWith(draw)
    await vi.advanceTimersByTimeAsync(1000)
    expect(act).toHaveBeenCalledTimes(1)
  })
})

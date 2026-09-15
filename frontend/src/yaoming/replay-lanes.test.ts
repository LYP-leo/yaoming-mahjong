import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ReplayPage from './ReplayPage.vue'
import YaomingApp from './YaomingApp.vue'
import TableView from './TableView.vue'
import SettlementDialog from './SettlementDialog.vue'
import { useYaomingStore, yaomingApi } from './store'
import { AUTO_DRAW_DELAY_MS } from './useAutoDraw'
import { player, result, room, tile } from './testFixtures'
import type { HandRecord, Identity, ReplayIdentity, ReplayList, RoomView } from './types'

const identity: Identity = { roomId: 'room1', playerId: 'p2', token: 'replay-lanes-local-secret' }
const wrappers: VueWrapper[] = []
function record(roomId = 'room1', round = 1): HandRecord {
  const settlement = result()
  return { roomId, roomName: '历史牌桌', round, roundLabel: '东一局', startedAt: 1000, completedAt: 6000,
    complete: true, incomplete: false, result: settlement,
    frames: Array.from({ length: 6 }, (_, index) => ({ index, timestamp: 1000 + index * 1000,
      type: index === 5 ? 'WIN' : 'DISCARD', actorSeat: index % 3, message: `历史步骤${index + 1}`,
      status: index === 5 ? 'HAND_END' : 'NEED_DISCARD', currentSeat: index % 3, wallCount: 60 - index,
      dealerSeat: index >= 3 ? 2 : 0, dice: { opening: [1, 2], breaking: [3, 4], openingSeat: 2, breakStack: 8 },
      players: [player(), player('p2', 1), player('p3', 2)].map(p => ({ ...p,
        hand: [{ ...tile, id: `${p.id}-hand`, rank: p.seat + 1, label: `玩家${p.seat + 1}历史手牌` }],
        drawnTileId: index === 2 && p.id === 'p2' ? 'p2-hand' : null,
        discards: p.id === 'p2' ? Array.from({ length: 17 }, (_, n) => ({ ...tile, id: `river-${n}`, rank: n % 9 + 1 })) : [],
      })), lastDiscard: { tile: { ...tile, id: 'river-16' }, fromSeat: 1, claimed: false, kind: 'TSUMOGIRI' },
      result: index === 5 ? settlement : null,
    })),
  }
}
function mockRecords(rounds = [1]) {
  return vi.spyOn(yaomingApi, 'get').mockImplementation(async (path, config) => {
    if (path === '/replays') {
      const roomId = String((config?.params as { roomId?: string } | undefined)?.roomId)
      const listing: ReplayList = { roomId, roomName: '历史牌桌', note: '', hands: rounds.map(round => ({
        round, roundLabel: `第${round}局`, startedAt: 1000, completedAt: 6000, frameCount: 6, incomplete: false, title: '历史结算',
      })) }
      return { data: listing }
    }
    const parts = String(path).split('/')
    return { data: record(parts[2], Number(parts[3])) }
  })
}
async function render(id: Identity | null = identity, history: ReplayIdentity[] = []) {
  const wrapper = mount(ReplayPage, { props: { identity: id, room: room(), history } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
const position = (wrapper: VueWrapper) => Number((wrapper.get('[aria-label="复盘进度"]').element as HTMLInputElement).value)
async function wheel(wrapper: VueWrapper, options: WheelEventInit = {}, selector = '.ym-replay-page') {
  const event = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 40, ...options })
  wrapper.get(selector).element.dispatchEvent(event); await nextTick(); return event
}

beforeEach(() => {
  vi.restoreAllMocks(); vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-11T12:00:00Z'))
  localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.clearAllTimers(); vi.useRealTimers() })

describe('shared readonly replay table', () => {
  it('adapts only the selected historical frame, shows all hands, and defaults perspective to the authenticated participant', async () => {
    mockRecords()
    const wrapper = await render(), table = wrapper.findComponent(TableView)
    expect(table.props('readonly')).toBe(true)
    expect(wrapper.findAll('.ym-play-layout.ym-lanes-layout .ym-play-main > .ym-table-scroll')).toHaveLength(1)
    expect(wrapper.findAll('.ym-player-lane')).toHaveLength(3)
    expect(wrapper.findAll('.ym-replay-players, .ym-replay-player')).toHaveLength(0)
    const initial = table.props('room') as RoomView
    expect(initial.meId).toBe('p2'); expect(initial.actions).toEqual([])
    expect(initial.deadlineAt).toBeNull(); expect(initial.deadlineKind).toBeNull()
    expect(initial.players.map(p => p.handSize)).toEqual([1, 1, 1])
    expect(initial.players.map(p => p.wind)).toEqual(['东', '南', '西'])
    expect(initial.dice).toEqual(record().frames[0].dice)
    expect(initial.result).toBeNull()
    expect(wrapper.find('.ym-replay-result').exists()).toBe(false)
    wrapper.findAll('.ym-player-lane').forEach(lane => expect(lane.findAll('svg.mahjong-art').length).toBeGreaterThan(0))
    expect(wrapper.get('.ym-lane-self').attributes('data-player-id')).toBe('p2')
    expect(wrapper.html()).not.toContain(identity.token)
    await wrapper.get('[aria-label="选择复盘视角"]').setValue('p3')
    expect(table.props('room').meId).toBe('p3')
    expect(wrapper.get('.ym-lane-self').attributes('data-player-id')).toBe('p3')
    await wrapper.get('[aria-label="复盘进度"]').setValue('3')
    expect(table.props('room').players.map((p: RoomView['players'][number]) => p.wind)).toEqual(['南', '西', '东'])
    expect(wrapper.findAll('.ym-player-lane').map(lane => lane.attributes('data-wind'))).toEqual(['东', '南', '西'])
    expect(table.props('room').meId).toBe('p3')
  })

  it('falls back to the first seat when the historical participant no longer exists in a frame', async () => {
    mockRecords()
    const wrapper = await render({ ...identity, playerId: 'legacy-participant' })
    expect(wrapper.findComponent(TableView).props('room').meId).toBe('p1')
    expect((wrapper.get('[aria-label="选择复盘视角"]').element as HTMLSelectElement).value).toBe('p1')
  })

  it('never submits actions to an existing live room from replay tiles, shortcuts, wheel or historical settlement', async () => {
    mockRecords()
    const store = useYaomingStore(); store.identity = { ...identity }; store.room = room(); store.connected = true
    const act = vi.spyOn(store, 'act').mockResolvedValue(true), post = vi.spyOn(yaomingApi, 'post')
    const before = JSON.stringify(store.room), wrapper = await render()
    const table = wrapper.findComponent(TableView)
    for (const button of table.findAll('.ym-hand-tile')) { expect(button.attributes('disabled')).toBeDefined(); await button.trigger('click') }
    for (const key of ['a', 'd', 'Enter', ' ']) window.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
    await wheel(wrapper)
    await wrapper.get('[aria-label="复盘进度"]').setValue('5')
    expect(table.props('room').result).toEqual(record().frames[5].result)
    await wrapper.get('.ym-replay-result button').trigger('click')
    expect(wrapper.findComponent(SettlementDialog).props('room').actions).toEqual([])
    expect(wrapper.findComponent(SettlementDialog).props('room').result).toEqual(record().result)
    expect(wrapper.findComponent(SettlementDialog).props('readonly')).toBe(true)
    expect(wrapper.find('.ym-result-footer').exists()).toBe(false)
    expect(wrapper.get('.ym-result-scores .ym-is-me > span small').text()).toBe('当前视角')
    expect((await wheel(wrapper, { deltaY: -40 })).defaultPrevented).toBe(false)
    expect(position(wrapper)).toBe(5)
    await wrapper.get('[aria-label="暂时收起结算"]').trigger('click')
    expect(table.emitted('action')).toBeUndefined(); expect(post).not.toHaveBeenCalled(); expect(act).not.toHaveBeenCalled()
    expect(JSON.stringify(store.room)).toBe(before)
  })

  it('keeps historical navigation separate from live pushes and cancels a queued automatic draw when the actual app opens replay', async () => {
    mockRecords()
    const store = useYaomingStore()
    store.identity = { ...identity }; store.connected = true
    store.room = room({ meId: 'p2', currentSeat: 1, status: 'NEED_DRAW', actions: [{ type: 'DRAW', label: '摸牌', tileIds: [] }] })
    vi.spyOn(store, 'start').mockResolvedValue()
    vi.spyOn(store, 'stop').mockImplementation(() => {})
    const act = vi.spyOn(store, 'act').mockResolvedValue(true), post = vi.spyOn(yaomingApi, 'post')
    const wrapper = mount(YaomingApp, { global: { stubs: { HintsPanel: true } } })
    wrappers.push(wrapper)
    await vi.advanceTimersByTimeAsync(AUTO_DRAW_DELAY_MS - 10)
    await wrapper.get('.ym-replay-entry button').trigger('click'); await flushPromises()
    expect(wrapper.findAllComponents(TableView)).toHaveLength(1)
    expect(wrapper.findComponent(TableView).props('readonly')).toBe(true)
    await vi.advanceTimersByTimeAsync(1000)
    expect(act).not.toHaveBeenCalled()
    store.room = room({ meId: 'p2', currentSeat: 1, status: 'NEED_DISCARD', round: 2, version: 99, message: '真实牌局的新消息' })
    await nextTick()
    const expectedLive = JSON.stringify(store.room)
    await wheel(wrapper)
    expect(position(wrapper)).toBe(1)
    expect(wrapper.findComponent(TableView).props('room').round).toBe(1)
    expect(wrapper.findComponent(TableView).props('room').message).toBe('历史步骤2')
    await wrapper.get('.ym-replay-heading > button').trigger('click')
    expect(wrapper.findComponent(ReplayPage).exists()).toBe(false)
    expect(wrapper.findComponent(TableView).props('readonly')).toBe(false)
    expect(wrapper.findComponent(TableView).props('room').version).toBe(99)
    await vi.advanceTimersByTimeAsync(1000)
    expect(JSON.stringify(store.room)).toBe(expectedLive)
    expect(act).not.toHaveBeenCalled(); expect(post).not.toHaveBeenCalled()
  })
})

describe('intrinsically readonly historical settlement', () => {
  it.each([false, true])('blocks mistakenly supplied live ACK/LEAVE actions and deadlines (matchOver=%s)', async matchOver => {
    const value = room({ status: matchOver ? 'MATCH_END' : 'HAND_END', meId: 'p2',
      result: result({ matchOver }), deadlineKind: 'SETTLEMENT', deadlineAt: new Date(Date.now() + 60_000).toISOString(),
      actions: [{ type: 'ACK', label: '确认', tileIds: [] }, { type: 'LEAVE', label: '离开', tileIds: [] }],
    })
    const snapshot = JSON.stringify(value)
    const wrapper = mount(SettlementDialog, { props: { room: value, busy: false, readonly: true } })
    wrappers.push(wrapper)
    expect(wrapper.find('.ym-result-footer').exists()).toBe(false)
    expect(wrapper.find('[role="timer"]').exists()).toBe(false)
    expect(wrapper.findAll('button')).toHaveLength(1)
    expect(wrapper.get('.ym-result-scores .ym-is-me > span small').text()).toBe('当前视角')
    await vi.advanceTimersByTimeAsync(61_000)
    for (const key of ['Enter', ' ']) window.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
    await wrapper.get('[aria-label="暂时收起结算"]').trigger('click')
    expect(wrapper.emitted('minimize')).toHaveLength(1)
    expect(wrapper.emitted('action')).toBeUndefined()
    expect(JSON.stringify(value)).toBe(snapshot)
  })

  it('guards stale confirmation handlers when a previously live settlement becomes readonly', async () => {
    const wrapper = mount(SettlementDialog, { props: {
      room: room({ result: result(), actions: [{ type: 'ACK', label: '确认', tileIds: [] }] }), busy: false,
    } })
    wrappers.push(wrapper)
    const oldConfirmation = wrapper.get('.ym-result-footer button').element as HTMLButtonElement
    expect(wrapper.get('.ym-result-scores .ym-is-me > span small').text()).toBe('你')
    await wrapper.setProps({ readonly: true })
    oldConfirmation.click()
    expect(wrapper.find('.ym-result-footer').exists()).toBe(false)
    expect(wrapper.emitted('action')).toBeUndefined()
    await wrapper.setProps({ readonly: false })
    await wrapper.get('.ym-result-footer button').trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
  })
})

describe('replay wheel navigation', () => {
  it('steps down and up while allowing native page scrolling only at the frame boundaries', async () => {
    mockRecords(); const wrapper = await render()
    expect((await wheel(wrapper)).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(1)
    await vi.advanceTimersByTimeAsync(100)
    expect((await wheel(wrapper, { deltaY: -40 })).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(0)
    await vi.advanceTimersByTimeAsync(100)
    expect((await wheel(wrapper, { deltaY: -80 })).defaultPrevented).toBe(false); expect(position(wrapper)).toBe(0)
    await wrapper.get('[aria-label="复盘进度"]').setValue('5')
    expect((await wheel(wrapper, { deltaY: 80 })).defaultPrevented).toBe(false); expect(position(wrapper)).toBe(5)
  })

  it('accumulates small trackpad deltas to 40 pixels and starts a fresh accumulation on direction reversal', async () => {
    mockRecords(); const wrapper = await render()
    expect((await wheel(wrapper, { deltaY: 15 })).defaultPrevented).toBe(true)
    expect((await wheel(wrapper, { deltaY: 15 })).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(0)
    expect((await wheel(wrapper, { deltaY: 10 })).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(1)
    await vi.advanceTimersByTimeAsync(100)
    expect((await wheel(wrapper, { deltaY: 30 })).defaultPrevented).toBe(true)
    expect((await wheel(wrapper, { deltaY: -30 })).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(1)
    expect((await wheel(wrapper, { deltaY: -10 })).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(0)
  })

  it.each([{ deltaMode: 1, deltaY: 3 }, { deltaMode: 2, deltaY: 1 }])('normalizes line/page wheel units %o', async options => {
    mockRecords(); const wrapper = await render()
    expect((await wheel(wrapper, options)).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(1)
    await vi.advanceTimersByTimeAsync(100)
    expect((await wheel(wrapper, { ...options, deltaY: -options.deltaY })).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(0)
  })

  it('throttles bursts to one step per 100 ms and does not turn one oversized event into many steps', async () => {
    mockRecords(); const wrapper = await render()
    expect((await wheel(wrapper, { deltaY: 10000 })).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(1)
    await vi.advanceTimersByTimeAsync(99)
    expect((await wheel(wrapper, { deltaY: 10000 })).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(1)
    await vi.advanceTimersByTimeAsync(1)
    expect((await wheel(wrapper)).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(2)
  })

  it.each([{ ctrlKey: true }, { metaKey: true }, { altKey: true }, { shiftKey: true }, { deltaX: 80 }, { deltaY: 0 }])('ignores zoom, modifier and horizontal gestures %o', async options => {
    mockRecords(); const wrapper = await render()
    expect((await wheel(wrapper, options)).defaultPrevented).toBe(false); expect(position(wrapper)).toBe(0)
  })

  it.each(['[aria-label="复盘进度"]', '[aria-label="选择复盘视角"]', '.ym-replay-buttons button'])('preserves native input/control wheel behavior on %s', async selector => {
    mockRecords(); const wrapper = await render()
    expect((await wheel(wrapper, {}, selector)).defaultPrevented).toBe(false); expect(position(wrapper)).toBe(0)
  })

  it('does not navigate while a full-river dialog is open and resumes only after it closes', async () => {
    mockRecords(); const wrapper = await render()
    await wrapper.get('.ym-lane-self .ym-lane-river-more').trigger('click')
    expect(wrapper.find('.ym-river-dialog').exists()).toBe(true)
    expect((await wheel(wrapper)).defaultPrevented).toBe(false); expect(position(wrapper)).toBe(0)
    await wrapper.get('[aria-label="关闭完整牌河"]').trigger('click')
    expect((await wheel(wrapper)).defaultPrevented).toBe(true); expect(position(wrapper)).toBe(1)
  })

  it('pauses automatic playback when a wheel step is accepted', async () => {
    mockRecords(); const wrapper = await render()
    await wrapper.findAll('.ym-replay-buttons button')[2].trigger('click')
    await vi.advanceTimersByTimeAsync(1000); expect(position(wrapper)).toBe(1)
    await wheel(wrapper); expect(position(wrapper)).toBe(2)
    expect(wrapper.findAll('.ym-replay-buttons button')[2].text()).toBe('播放')
    await vi.advanceTimersByTimeAsync(2000); expect(position(wrapper)).toBe(2)
  })

  it('resets accumulated motion when scrubbing, selecting a hand or changing participant identity', async () => {
    mockRecords([1, 2]); const wrapper = await render()
    await wheel(wrapper, { deltaY: 30 })
    await wrapper.get('[aria-label="复盘进度"]').setValue('2')
    await wheel(wrapper, { deltaY: 10 }); expect(position(wrapper)).toBe(2)
    await wrapper.get('[aria-label="选择复盘小局"]').setValue('1'); await flushPromises()
    await wheel(wrapper, { deltaY: 30 }); expect(position(wrapper)).toBe(0)
    await wrapper.setProps({ identity: { ...identity, roomId: 'room2', token: 'another-local-secret' } }); await flushPromises()
    await wheel(wrapper, { deltaY: 10 }); expect(position(wrapper)).toBe(0)
    await wheel(wrapper, { deltaY: 30 }); expect(position(wrapper)).toBe(1)
    expect(wrapper.findComponent(TableView).props('room').id).toBe('room2')
  })

  it('leaves wheel events untouched during loading and for completed records with no frames', async () => {
    let deliver!: (value: unknown) => void
    vi.spyOn(yaomingApi, 'get').mockImplementation(async path => path === '/replays'
      ? { data: { roomId: 'room1', roomName: '历史牌桌', note: '', hands: [{ round: 1 }] } }
      : await new Promise<unknown>(resolve => { deliver = resolve }) as never)
    const wrapper = await render()
    expect((await wheel(wrapper)).defaultPrevented).toBe(false)
    deliver({ data: { ...record(), frames: [] } }); await flushPromises()
    expect(wrapper.text()).toContain('没有可播放的牌桌帧')
    expect((await wheel(wrapper)).defaultPrevented).toBe(false)
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ReplayPage from './ReplayPage.vue'
import TableView from './TableView.vue'
import HintsPanel from './HintsPanel.vue'
import { yaomingApi } from './store'
import { replayJson } from './replayExport'
import { player, result, room, tile } from './testFixtures'
import type { HandRecord, RuleId, ReplayList, HintResponse } from './types'

const identity = { roomId: 'historic', playerId: 'p4', token: 'private-four-player-token' }
const wrappers: VueWrapper[] = []
const keep = <T extends VueWrapper>(wrapper: T) => { wrappers.push(wrapper); return wrapper }
function record(ruleId?: RuleId): HandRecord {
  const count = ruleId === 'yaoming-4p' ? 4 : 3
  const players = Array.from({ length: count }, (_, seat) => ({ ...player(`p${seat + 1}`, seat),
    hand: Array.from({ length: 13 }, (_, i) => ({ ...tile, id: `historic-${seat}-${i}` })),
    drawnTileId: null,
  }))
  const outcome = result({ items: [{ id: 'MENQING', name: '当时门清', fan: 7, description: '历史保存的测试数值，不采用当前规则重算' }], rawFan: 7, fan: 7,
    scores: players.map((p, i) => ({ playerId: p.id, name: p.name, score: 10, delta: 0, rank: i + 1 })),
    hands: players.map(p => ({ playerId: p.id, hand: p.hand, melds: [] })), payments: [] })
  return { roomId: 'historic', roomName: '已结束牌桌', ...(ruleId ? { ruleId, capacity: count, ruleName: `${count}人归档规则` } : {}),
    round: 1, roundLabel: '东一局', startedAt: 1000, completedAt: 2000, complete: true, incomplete: false, result: outcome,
    frames: [0, 1].map(index => ({ index, timestamp: 1000 + index * 1000, type: index ? 'WIN' : 'DRAW', actorSeat: count - 1,
      status: index ? 'HAND_END' : 'NEED_DISCARD', currentSeat: count - 1, dealerSeat: count - 1, wallCount: 30, message: '历史行动',
      players, lastDiscard: null, dice: null, result: index ? outcome : null })),
  }
}
function mockRecord(hand: HandRecord) {
  const listing: ReplayList = { roomId: 'historic', roomName: '已结束牌桌', note: '', hands: [{ round: 1, roundLabel: hand.roundLabel,
    startedAt: 1000, completedAt: 2000, frameCount: 2, incomplete: false, title: '历史结束' }] }
  return vi.spyOn(yaomingApi, 'get').mockImplementation(async path => ({ data: path === '/replays' ? listing : hand }))
}
beforeEach(() => { vi.restoreAllMocks(); vi.useFakeTimers(); localStorage.clear(); sessionStorage.clear() })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks() })

describe('archived rule identity, not the active live room', () => {
  it('treats missing archival rule ID as three players even if capacity says four', async () => {
    const hand = { ...record(), capacity: 4 }; mockRecord(hand)
    const wrapper = keep(mount(ReplayPage, { props: { identity, history: [], room: null } })); await flushPromises()
    expect(wrapper.getComponent(TableView).props('room').capacity).toBe(3)
    expect(wrapper.findAll('.ym-player-lane')).toHaveLength(3)
    expect(JSON.parse(replayJson(hand)).ruleId).toBe('yaoming-3p')
    expect(JSON.parse(replayJson(hand)).capacity).toBe(3)
  })

  it('rejects an unknown historical hand profile before rendering a replay table', async () => {
    mockRecord({ ...record(), ruleId: 'future-profile' as RuleId })
    const wrapper = keep(mount(ReplayPage, { props: { identity, history: [], room: null } })); await flushPromises()
    expect(wrapper.findComponent(TableView).exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('当前版本不支持此规则')
    expect(wrapper.text()).not.toContain('导出 JSON')
  })

  it.each(['listing', 'summary'])('rejects unknown %s profile before requesting the historical hand', async target => {
    const hand = record()
    const summary = { round: 1, roundLabel: '东一局', frameCount: 2, title: '结束', ...(target === 'summary' ? { ruleId: 'future-profile' } : {}) }
    const listing = { roomId: 'historic', roomName: '旧局', hands: [summary], ...(target === 'listing' ? { ruleId: 'future-profile' } : {}) }
    const get = vi.spyOn(yaomingApi, 'get').mockImplementation(async path => ({ data: path === '/replays' ? listing : hand }))
    const wrapper = keep(mount(ReplayPage, { props: { identity, history: [], room: null } })); await flushPromises()
    expect(get).toHaveBeenCalledTimes(1)
    expect(wrapper.findComponent(TableView).exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('请更新页面后重试')
  })

  it.each(['yaoming-3p', 'yaoming-4p', undefined] as const)('uses archived metadata %s even when the live room uses the other profile', async ruleId => {
    const hand = record(ruleId), count = ruleId === 'yaoming-4p' ? 4 : 3
    mockRecord(hand)
    const id = { ...identity, playerId: `p${count}` }, original = JSON.stringify(hand)
    const wrapper = keep(mount(ReplayPage, { props: { identity: id, history: [], room: room({ ruleId: count === 4 ? 'yaoming-3p' : 'yaoming-4p', capacity: count === 4 ? 3 : 4 }) } }))
    await flushPromises()
    const shared = wrapper.getComponent(TableView), adapted = shared.props('room')
    expect(adapted.capacity).toBe(count)
    expect(adapted.ruleId).toBe(ruleId || 'yaoming-3p')
    expect(adapted.players.map(p => p.wind)).toEqual(count === 4 ? ['南', '西', '北', '东'] : ['南', '西', '东'])
    expect(wrapper.findAll('.ym-player-lane').map(lane => lane.attributes('data-wind'))).toEqual(count === 4 ? ['东', '南', '西', '北'] : ['东', '南', '西'])
    expect(wrapper.findAll('.ym-lanes-table .mahjong-art')).toHaveLength(count * 13)
    expect(wrapper.findAll('.ym-lanes-table .ym-tile-back')).toHaveLength(0)
    expect(shared.props('readonly')).toBe(true); expect(adapted.actions).toEqual([])
    expect(JSON.stringify(hand)).toBe(original)
  })

  it('supports all four perspectives while keeping saved fan values and action isolation', async () => {
    const hand = record('yaoming-4p'); mockRecord(hand)
    const wrapper = keep(mount(ReplayPage, { props: { identity, history: [], room: null } })); await flushPromises()
    for (const owner of hand.frames[0].players) {
      await wrapper.get('[aria-label="选择复盘视角"]').setValue(owner.id)
      expect(wrapper.getComponent(TableView).props('room').meId).toBe(owner.id)
      expect(wrapper.get('.ym-lane-self').attributes('data-player-id')).toBe(owner.id)
      expect(wrapper.get('.ym-player-panel').text()).toContain(owner.name)
    }
    await wrapper.findAll('.ym-replay-buttons button').find(button => button.text() === '末步')!.trigger('click')
    expect(wrapper.get('.ym-replay-result').text()).toContain('当时门清 7番')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }))
    expect(wrapper.getComponent(TableView).emitted('action')).toBeUndefined()
  })

  it.each(['yaoming-3p', 'yaoming-4p', undefined] as const)('exports safe immutable rule metadata for %s without credentials', ruleId => {
    const hand = record(ruleId), original = JSON.stringify(hand)
    const parsed = JSON.parse(replayJson(Object.assign(hand, { token: identity.token, futureSecret: identity.token })))
    expect(parsed.ruleId).toBe(ruleId || 'yaoming-3p')
    expect(parsed.capacity).toBe(ruleId === 'yaoming-4p' ? 4 : 3)
    expect(parsed.result.items[0].fan).toBe(7)
    expect(JSON.stringify(parsed)).not.toContain(identity.token)
    delete (hand as HandRecord & { token?: string }).token; delete (hand as HandRecord & { futureSecret?: string }).futureSecret
    expect(JSON.stringify(hand)).toBe(original)
  })
})

describe('listening display rule scope', () => {
  it('does not carry three-player wait results into four-player rule metadata', async () => {
    const own = { roomId: 'room1', playerId: 'p1', token: 'hint-rule-token' }
    const answer: HintResponse = { roomId: own.roomId, playerId: own.playerId, version: 1, analysis: { mode: 'WAIT', note: '', discards: [],
      waits: [{ tile, unseenCount: 2, canRon: true, ronFan: 4, ronReason: '', canTsumo: true, tsumoFan: 4 }] } }
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: answer })
    const wrapper = keep(mount(HintsPanel, { props: { identity: own, room: room({ ruleId: 'yaoming-3p' }) } }))
    await vi.advanceTimersByTimeAsync(200); await flushPromises()
    expect(wrapper.findAll('.ym-hint-tile')).toHaveLength(1)
    await wrapper.setProps({ room: room({ ruleId: 'yaoming-4p', capacity: 4, version: 2 }) })
    expect(wrapper.findAll('.ym-hint-tile')).toHaveLength(0)
  })
})

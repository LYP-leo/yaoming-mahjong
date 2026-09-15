import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import HintsPanel from './HintsPanel.vue'
import ReplayPage from './ReplayPage.vue'
import TableView from './TableView.vue'
import { useYaomingStore, yaomingApi } from './store'
import { readReplayHistory, rememberReplayIdentity, removeReplayIdentity } from './replayHistory'
import { replayJson } from './replayExport'
import { player, result, room, tile } from './testFixtures'
import type { HandRecord, HintResponse, Identity, ReplayIdentity, ReplayList } from './types'

const identity: Identity = { roomId: 'room1', playerId: 'p1', token: 'private-secret' }
const history: ReplayIdentity[] = [{ ...identity, roomName: '原牌桌', playerName: '自己', savedAt: 1 }]
const hints = (overrides: Partial<HintResponse> = {}): HintResponse => ({ roomId: 'room1', playerId: 'p1', version: 1, analysis: {
  mode: 'WAIT', note: '结构进张还需达到 4 番', discards: [], waits: [{ tile, unseenCount: 2, canTsumo: true, tsumoFan: 4, canRon: false, ronFan: 3, ronReason: '点和不足 4 番' }],
}, ...overrides })
const record = (overrides: Partial<HandRecord> = {}): HandRecord => ({ roomId: 'room1', roomName: '原牌桌', round: 1, roundLabel: '东一局', startedAt: 1000, completedAt: 5000, complete: true, incomplete: false,
  frames: [0, 1, 2].map(index => ({ index, timestamp: 1000 + 2000 * index, type: index ? 'DISCARD' : 'START', actorSeat: index ? 0 : -1,
    message: `记录步骤${index + 1}`, status: index === 2 ? 'HAND_END' : 'NEED_DISCARD', currentSeat: 0, wallCount: 68 - index, dealerSeat: 0,
    players: [player(), { ...player('p2', 1), hand: [{ ...tile, id: 'p2-tile', label: '二条' }] }, player('p3', 2)],
    lastDiscard: index ? { tile, fromSeat: 0, claimed: index === 2 } : null, dice: null, result: index === 2 ? result() : null })), result: result(), ...overrides })
const listing: ReplayList = { roomId: 'room1', roomName: '原牌桌', hands: [{ round: 1, roundLabel: '东一局', startedAt: 1000, completedAt: 5000, frameCount: 3, incomplete: false, title: '自摸' }], note: '' }

beforeEach(() => { vi.restoreAllMocks(); localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia()) })
afterEach(() => { vi.useRealTimers() })

describe('private, automatic listening assistance', () => {
  beforeEach(() => { vi.useFakeTimers() })
  async function analyze() { await vi.advanceTimersByTimeAsync(200); await flushPromises() }

  it('calculates silently with header authentication and exposes the unseen-count definition only accessibly', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: hints() })
    const wrapper = mount(HintsPanel, { props: { identity, room: room() } })
    expect(wrapper.text()).toBe(''); expect(wrapper.findAll('button')).toHaveLength(0)
    expect(get).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(199); expect(get).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1); await flushPromises()
    expect(get).toHaveBeenCalledWith('/rooms/room1/hints', expect.objectContaining({ params: { playerId: 'p1' }, headers: { 'X-Resume-Token': 'private-secret' }, signal: expect.any(AbortSignal) }))
    expect(wrapper.get('.ym-hint-count').text()).toBe('2张')
    expect(wrapper.get('.ym-hint-count').attributes('aria-label')).toContain('不是牌墙剩余张数')
    expect(wrapper.get('.ym-hint-fans').text()).toBe('自摸4番')
    expect(wrapper.text()).not.toMatch(/不能点和|结构进张|版本|自动分析/)
    expect(wrapper.html()).not.toContain(identity.token)
    wrapper.unmount()
  })
  it('retains successful tiles during background version refresh and deduplicates same-version heartbeats', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: hints() }).mockResolvedValueOnce({ data: hints({ version: 2 }) })
    const wrapper = mount(HintsPanel, { props: { identity, room: room() } })
    await analyze()
    const displayed = wrapper.get('.ym-hint-waits').element
    await wrapper.setProps({ room: room({ version: 2 }) })
    expect(wrapper.get('.ym-hint-waits').element).toBe(displayed)
    expect(get).toHaveBeenCalledTimes(1)
    await analyze(); expect(get).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.ym-hint-waits').element).toBe(displayed)
    await wrapper.setProps({ room: room({ version: 2, serverTime: '2026-09-09T01:00:00Z', clientReceivedAt: 12345 }) })
    await analyze(); expect(get).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it.each(['version', 'identity'])('discards an in-flight result if the %s changed', async change => {
    let deliver!: (value: unknown) => void
    vi.spyOn(yaomingApi, 'get').mockReturnValue(new Promise(resolve => { deliver = resolve }) as never)
    const wrapper = mount(HintsPanel, { props: { identity, room: room() } })
    await analyze()
    if (change === 'version') await wrapper.setProps({ room: room({ version: 2 }) })
    else await wrapper.setProps({ identity: { ...identity, playerId: 'p2' } })
    deliver({ data: hints() }); await flushPromises()
    expect(wrapper.find('.ym-hint-waits').exists()).toBe(false)
    wrapper.unmount()
  })
  it.each([{ roomId: 'other' }, { playerId: 'p2' }, { version: 2 }])('refuses a mismatched response %o', async mismatch => {
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: hints(mismatch) })
    const wrapper = mount(HintsPanel, { props: { identity, room: room() } })
    await analyze()
    expect(wrapper.find('.ym-hint-waits').exists()).toBe(false)
    wrapper.unmount()
  })
  it('filters empty and below-threshold discards and shows eligible rows as tile, arrow and waits without expansion', async () => {
    const response = hints(); response.analysis.mode = 'DISCARD'
    response.analysis.discards = [{ tile, waits: [] }, { tile: { ...tile, id: 'other' }, waits: [{ ...response.analysis.waits[0], canTsumo: false, tsumoFan: 2 }] },
      { tile: { ...tile, id: 'eligible', suit: 'DOTS' }, waits: response.analysis.waits }]
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: response })
    const wrapper = mount(HintsPanel, { props: { identity, room: room() } })
    await analyze()
    expect(wrapper.findAll('.ym-discard-hint')).toHaveLength(1)
    expect(wrapper.get('.ym-discard-hint').element.tagName).toBe('DIV')
    expect(wrapper.findAll('.ym-hint-arrow')).toHaveLength(1)
    expect(wrapper.findAll('details,summary,button')).toHaveLength(0)
    expect(wrapper.get('.ym-hint-fans').text()).toBe('自摸4番')
    expect(wrapper.text()).not.toMatch(/进张|门槛/)
    wrapper.unmount()
  })
  it('shows no possible listening discard if all legal candidates have zero waits', async () => {
    const response = hints(); response.analysis.mode = 'DISCARD'; response.analysis.discards = [{ tile, waits: [] }]
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: response })
    const wrapper = mount(HintsPanel, { props: { identity, room: room() } })
    await analyze()
    expect(wrapper.text()).toBe(''); expect(wrapper.findAll('.ym-discard-hint')).toHaveLength(0)
    wrapper.unmount()
  })
  it.each([400, 403, 404, 500])('keeps failure %s silent and recovers through identity renewal or background retry', async status => {
    vi.spyOn(yaomingApi, 'get').mockRejectedValueOnce({ response: { status, data: { message: '身份无效' } } }).mockResolvedValueOnce({ data: hints() })
    const wrapper = mount(HintsPanel, { props: { identity, room: room() } })
    await analyze()
    expect(wrapper.text()).toBe(''); expect(wrapper.findAll('button,[role="alert"]')).toHaveLength(0)
    if (status === 500) { await vi.advanceTimersByTimeAsync(1000); await flushPromises() }
    else { await wrapper.setProps({ identity: { ...identity, token: 'renewed' } }); await analyze() }
    expect(wrapper.find('[role="alert"]').exists()).toBe(false); expect(wrapper.find('.ym-hint-waits').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('participant-only readonly replay', () => {
  function apiReplay(data = record()) {
    return vi.spyOn(yaomingApi, 'get').mockImplementation(async path => ({ data: path === '/replays' ? listing : data }))
  }
  it('loads the current seat before history, never exposes tokens in HTML, and reveals all completed-hand players without game actions', async () => {
    const get = apiReplay(), post = vi.spyOn(yaomingApi, 'post')
    const wrapper = mount(ReplayPage, { props: { identity, room: room(), history: [{ ...history[0], token: 'stale-secret' }] } })
    await flushPromises()
    expect(get).toHaveBeenCalledWith('/replays', { params: { roomId: 'room1', playerId: 'p1' }, headers: { 'X-Resume-Token': 'private-secret' } })
    expect(wrapper.text()).toContain('操作和结算倒计时照常继续')
    expect(wrapper.findAll('.ym-player-lane')).toHaveLength(3)
    expect(wrapper.get('[aria-label="玩家2 的区域"] .ym-concealed-hand').text()).toContain('二条')
    expect(wrapper.html()).not.toContain('private-secret'); expect(wrapper.html()).not.toContain('stale-secret')
    expect(wrapper.findComponent(TableView).props('readonly')).toBe(true)
    wrapper.findAll('.ym-hand-tile').forEach(button => expect(button.attributes('disabled')).toBeDefined())
    expect(post).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('supports first/last/previous/next and clamps the slider without emitting gameplay', async () => {
    apiReplay()
    const wrapper = mount(ReplayPage, { props: { identity: null, room: null, history } })
    await flushPromises()
    const controls = wrapper.findAll('.ym-replay-buttons button')
    expect(controls[0].attributes('disabled')).toBeDefined(); expect(wrapper.text()).toContain('记录步骤1')
    await controls[3].trigger('click'); expect(wrapper.text()).toContain('记录步骤2')
    await controls[4].trigger('click'); expect(wrapper.text()).toContain('记录步骤3'); expect(controls[4].attributes('disabled')).toBeDefined()
    await controls[1].trigger('click'); expect(wrapper.text()).toContain('记录步骤2')
    await controls[0].trigger('click'); expect(wrapper.text()).toContain('记录步骤1')
    await wrapper.get('input[type="range"]').setValue('2'); expect(wrapper.text()).toContain('记录步骤3')
    expect(wrapper.emitted('action')).toBeUndefined()
    wrapper.unmount()
  })
  it('plays one step per second, stops at the end, restarts from the beginning and clears its timer on unmount', async () => {
    vi.useFakeTimers(); apiReplay()
    const wrapper = mount(ReplayPage, { props: { identity: null, room: null, history } })
    await flushPromises()
    await wrapper.findAll('.ym-replay-buttons button')[2].trigger('click')
    await vi.advanceTimersByTimeAsync(1000); expect(wrapper.text()).toContain('记录步骤2')
    await vi.advanceTimersByTimeAsync(1000); expect(wrapper.text()).toContain('记录步骤3')
    expect(wrapper.findAll('.ym-replay-buttons button')[2].text()).toBe('播放')
    await wrapper.findAll('.ym-replay-buttons button')[2].trigger('click'); expect(wrapper.text()).toContain('记录步骤1')
    wrapper.unmount(); expect(vi.getTimerCount()).toBe(0)
  })
  it('refuses a mistakenly returned unfinished record rather than revealing hand tiles', async () => {
    apiReplay(record({ complete: false }))
    const wrapper = mount(ReplayPage, { props: { identity, room: room(), history } })
    await flushPromises()
    expect(wrapper.text()).toContain('该小局尚未结束'); expect(wrapper.find('.ym-replay-frame').exists()).toBe(false)
    wrapper.unmount()
  })
  it('explains incomplete historical frames without inventing playback', async () => {
    apiReplay(record({ incomplete: true, frames: [] }))
    const wrapper = mount(ReplayPage, { props: { identity, room: room({ status: 'MATCH_END' }), history } })
    await flushPromises()
    expect(wrapper.text()).toContain('这份牌谱记录不完整'); expect(wrapper.text()).toContain('没有可播放的牌桌帧')
    expect(wrapper.find('.ym-replay-live').exists()).toBe(false)
    wrapper.unmount()
  })
  it.each([400, 403, 404])('explains authorization/retention failure %s', async status => {
    vi.spyOn(yaomingApi, 'get').mockRejectedValue({ response: { status, data: { message: '身份无效' } } })
    const wrapper = mount(ReplayPage, { props: { identity: null, room: null, history } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain(status === 404 ? '尚未生成或已超出保存范围' : '仅本场参赛者')
    wrapper.unmount()
  })
  it('discards a completed-hand response after switching to another history identity', async () => {
    let deliver!: (value: unknown) => void
    vi.spyOn(yaomingApi, 'get').mockImplementation(async path => path === '/replays' ? { data: listing } : await new Promise<unknown>(resolve => { deliver = resolve }) as never)
    const wrapper = mount(ReplayPage, { props: { identity: null, room: null, history } })
    await flushPromises()
    await wrapper.setProps({ history: [] })
    deliver({ data: record() }); await flushPromises()
    expect(wrapper.find('.ym-replay-frame').exists()).toBe(false); expect(wrapper.text()).toContain('暂无本机历史')
    wrapper.unmount()
  })
})

describe('local replay credentials and safe exports', () => {
  it('limits history to twenty seats, deduplicates and refreshes credentials without touching active identity storage', () => {
    sessionStorage.setItem('yaoming.identity.v1', JSON.stringify(identity))
    for (let n = 0; n < 23; n++) rememberReplayIdentity({ ...identity, roomId: `r${n}` }, `牌桌${n}`)
    expect(readReplayHistory()).toHaveLength(20); expect(readReplayHistory()[0].roomId).toBe('r22')
    rememberReplayIdentity({ ...identity, roomId: 'r22', token: 'replacement' })
    expect(readReplayHistory()).toHaveLength(20); expect(readReplayHistory()[0].token).toBe('replacement')
    removeReplayIdentity({ ...identity, roomId: 'r22' })
    expect(readReplayHistory()).toHaveLength(19)
    expect(JSON.parse(sessionStorage.getItem('yaoming.identity.v1')!)).toEqual(identity)
  })
  it('retains a participant credential after successful LEAVE and after temporary detach', async () => {
    vi.spyOn(yaomingApi, 'post').mockResolvedValue({ data: null }); vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: [] })
    const store = useYaomingStore(); store.identity = identity; store.room = room()
    await store.act(store.room.actions[1])
    expect(store.identity).toBeNull(); expect(readReplayHistory()[0]).toMatchObject(identity)
    store.identity = { ...identity, roomId: 'r2' }; store.room = room({ id: 'r2' }); store.detach()
    expect(readReplayHistory()[0].roomId).toBe('r2'); expect(store.identity).toBeNull()
  })
  it('ignores malformed history instead of preventing startup', () => {
    localStorage.setItem('yaoming.replayHistory.v1', '{bad')
    expect(readReplayHistory()).toEqual([])
    localStorage.setItem('yaoming.replayHistory.v1', JSON.stringify([null, {}, { roomId: 5, playerId: 'p', token: 't' }, identity]))
    expect(readReplayHistory()).toHaveLength(1)
  })
  it('exports only whitelisted public frame fields, never recovery credentials or wall order', () => {
    const data = record() as HandRecord & { token: string; identity: Identity; wall: unknown[] }
    data.token = 'secret-top'; data.identity = identity; data.wall = [{ id: 'hidden-wall' }]
    Object.assign(data.frames[0].players[0], { token: 'secret-player', resumeToken: 'secret-resume' })
    Object.assign(data.frames[0].players[0].hand[0], { token: 'secret-tile' })
    const exported = replayJson(data)
    expect(exported).not.toMatch(/secret|token|identity|hidden-wall/i)
    expect(JSON.parse(exported).frames).toHaveLength(3)
    expect(JSON.parse(exported).frames[2].result.scores).toEqual(data.result?.scores)
  })
  it('refuses to export unfinished hands', () => { expect(() => replayJson(record({ complete: false }))).toThrow('只能导出已结束小局') })
})

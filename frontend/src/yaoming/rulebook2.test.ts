import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import YaomingApp from './YaomingApp.vue'
import RulesPage from './RulesPage.vue'
import ReplayPage from './ReplayPage.vue'
import SettlementDialog from './SettlementDialog.vue'
import WaitTiles from './WaitTiles.vue'
import { useYaomingStore, yaomingApi } from './store'
import { result, room, tile } from './testFixtures'
import type { Fan, HandRecord, Rules, WaitHint } from './types'

const wrappers: VueWrapper[] = []
const keep = <T extends VueWrapper>(wrapper: T): T => { wrappers.push(wrapper); return wrapper }
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.restoreAllMocks() })

const revision = '依据本次提供的《要命麻将规则集(2)》，封面版本仍为26.9 LTS；本次修订新增平和1番、门清2番、清全带幺3番，不再计断幺。'
const updatedFans: Fan[] = [
  { id: 'PINGHE', name: '平和', fan: 1, description: '四组顺子与一组数牌雀头；允许副露，159 万也算顺子。' },
  { id: 'MENQING', name: '门清', fan: 2, description: '没有吃、碰、明杠或加杠；允许暗杠。' },
  { id: 'QINGQUANDAIYAO', name: '清全带幺', fan: 3, description: '每个面子与雀头都含幺九，不含字牌；不计混全带幺。' },
  { id: 'ZIYISE', name: '字一色', fan: 6, description: '只有字牌；不计清一色、混全带幺、碰碰和、番牌。' },
]
const rules: Rules = { name: '要命麻将', version: '26.9 LTS', description: '', notes: [revision, '字一色不再叠加清一色；风龙只可加不求人、杠上炮。'], tiles: [tile], fans: updatedFans }

describe('rulebook file revision presentation', () => {
  it('waits for authoritative notes and catalog, then shows the supplied file revision once without inventing a version', async () => {
    const wrapper = keep(mount(RulesPage, { props: { rules: null } }))
    expect(wrapper.find('.ym-rule-revision').exists()).toBe(false)
    expect(wrapper.findAll('.ym-fan-grid article')).toHaveLength(0)
    const snapshot = JSON.stringify(rules)
    await wrapper.setProps({ rules })
    expect(wrapper.get('.ym-rule-revision').text()).toBe(revision)
    expect(wrapper.text().split(revision)).toHaveLength(2)
    expect(wrapper.get('.ym-overline').text()).toBe('THE RULEBOOK · 26.9 LTS')
    expect(wrapper.findAll('.ym-fan-grid article h3').map(card => card.text())).toEqual(updatedFans.map(fan => fan.name))
    expect(wrapper.findAll('.ym-fan-grid article strong').map(card => card.text())).toEqual(['1 番', '2 番', '3 番', '6 番'])
    expect(wrapper.text()).toContain('字一色不再叠加清一色')
    expect(wrapper.text()).toContain('已完成的结算和旧牌谱保留当时记录，不按新版规则重算')
    expect(JSON.stringify(rules)).toBe(snapshot)
  })

  it.each(['平和', '159 万也算顺子', '允许副露'])('searches the new server-supplied PINGHE definition: %s', async query => {
    const wrapper = keep(mount(RulesPage, { props: { rules } }))
    await wrapper.get('[aria-label="搜索番种"]').setValue(query)
    expect(wrapper.findAll('.ym-fan-grid article h3').map(card => card.text())).toEqual(['平和'])
    expect(wrapper.get('.ym-fan-grid article p').text()).toBe(updatedFans[0].description)
  })

  it('does not reinstate DUANYAO when it has disappeared from the server catalog', async () => {
    const wrapper = keep(mount(RulesPage, { props: { rules } }))
    await wrapper.get('[aria-label="搜索番种"]').setValue('断幺')
    expect(wrapper.findAll('.ym-fan-grid article')).toHaveLength(0)
    expect(wrapper.text()).toContain('没有找到对应番种')
  })

  it('renders new server values and descriptions directly, without locally overriding them', async () => {
    const wrapper = keep(mount(RulesPage, { props: { rules } }))
    const supplied = { ...rules, notes: ['服务端修订说明'], fans: [{ ...updatedFans[0], fan: 7, description: '仅用于证明客户端使用服务器数据，不是另一条本地规则。' }] }
    await wrapper.setProps({ rules: supplied })
    expect(wrapper.get('.ym-rule-revision').text()).toBe(supplied.notes[0])
    expect(wrapper.get('.ym-fan-grid article strong').text()).toBe('7 番')
    expect(wrapper.get('.ym-fan-grid article p').text()).toBe(supplied.fans[0].description)
    expect(wrapper.text()).not.toContain(revision)
  })
})

describe('rulebook reopens with current server data without clearing the existing catalog', () => {
  const previous: Rules = { ...rules, notes: ['旧规则文件'], fans: [
    { id: 'MENQING', name: '门清', fan: 1, description: '旧版门清。' },
    { id: 'DUANYAO', name: '断幺', fan: 2, description: '旧版断幺。' },
  ] }
  function app() {
    localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
    const store = useYaomingStore()
    store.rules = previous
    vi.spyOn(store, 'start').mockResolvedValue()
    vi.spyOn(store, 'stop').mockImplementation(() => {})
    return { store, wrapper: keep(mount(YaomingApp)) }
  }
  async function navigate(wrapper: VueWrapper, label: string) {
    await wrapper.findAll('.ym-nav-links button').find(button => button.text() === label)!.trigger('click')
  }

  it('requests the catalog on every opening, keeping the old cards until the new revision arrives', async () => {
    let deliver!: (response: { data: Rules }) => void
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: previous })
      .mockReturnValueOnce(new Promise<{ data: Rules }>(resolve => { deliver = resolve }) as never)
    const { store, wrapper } = app()
    await navigate(wrapper, '规则手册'); await flushPromises()
    expect(get).toHaveBeenCalledExactlyOnceWith('/rules')
    expect(wrapper.get('.ym-rule-revision').text()).toBe('旧规则文件')
    await navigate(wrapper, '游戏大厅')
    await navigate(wrapper, '规则手册')
    expect(get).toHaveBeenCalledTimes(2)
    const oldMenqing = wrapper.findAll('.ym-fan-grid article').find(card => card.get('h3').text() === '门清')!.element
    expect(wrapper.findAll('.ym-fan-grid article strong').map(card => card.text())).toEqual(['1 番', '2 番'])
    expect(wrapper.text()).not.toContain('规则正在加载')
    expect(store.rules?.notes[0]).toBe('旧规则文件')
    deliver({ data: rules }); await flushPromises()
    expect(wrapper.get('.ym-rule-revision').text()).toBe(revision)
    expect(wrapper.findAll('.ym-fan-grid article h3').map(card => card.text())).toEqual(updatedFans.map(fan => fan.name))
    expect(wrapper.findAll('.ym-fan-grid article strong').map(card => card.text())).toEqual(['1 番', '2 番', '3 番', '6 番'])
    expect(wrapper.findAll('.ym-fan-grid article').find(card => card.get('h3').text() === '门清')!.element).toBe(oldMenqing)
    expect(store.rules?.version).toBe(previous.version)
  })

  it('retains the visible catalog on a failed refresh and retries on the next opening', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockRejectedValueOnce(new Error('Network Error')).mockResolvedValueOnce({ data: rules })
    const { store, wrapper } = app()
    await navigate(wrapper, '规则手册'); await flushPromises()
    expect(wrapper.findAll('.ym-fan-grid article')).toHaveLength(2)
    expect(wrapper.get('.ym-rule-revision').text()).toBe('旧规则文件')
    expect(store.rules?.notes[0]).toBe('旧规则文件')
    await navigate(wrapper, '游戏大厅')
    await navigate(wrapper, '规则手册'); await flushPromises()
    expect(get).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.ym-rule-revision').text()).toBe(revision)
    expect(wrapper.findAll('.ym-fan-grid article')).toHaveLength(updatedFans.length)
  })
})

describe('revised fan downstream authority', () => {
  it('updates wait fan numbers in place from the supplied response, retaining the same tile and zero unseen count', async () => {
    const wait: WaitHint = { tile, unseenCount: 0, canRon: true, ronFan: 4, canTsumo: true, tsumoFan: 5, ronReason: '' }
    const wrapper = keep(mount(WaitTiles, { props: { waits: [wait] } }))
    const tileElement = wrapper.get('.ym-hint-tile').element
    expect(wrapper.get('.ym-hint-fans').text()).toBe('点和4番自摸5番')
    await wrapper.setProps({ waits: [{ ...wait, ronFan: 5, tsumoFan: 6 }] })
    expect(wrapper.get('.ym-hint-tile').element).toBe(tileElement)
    expect(wrapper.get('.ym-hint-fans').text()).toBe('点和5番自摸6番')
    expect(wrapper.get('.ym-hint-count').text()).toBe('0张')
    expect(wait.ronFan).toBe(4)
  })

  it('shows a new 4-fan pinfu win as server PINGHE 1 + MENQING 2 + BUQIUREN 1, without inventing DUANYAO', () => {
    const items: Fan[] = [updatedFans[0], updatedFans[1], { id: 'BUQIUREN', name: '不求人', fan: 1, description: '门清自摸成和。' }]
    const outcome = result({ items, rawFan: 4, fan: 4 })
    const before = JSON.stringify(outcome)
    const wrapper = keep(mount(SettlementDialog, { props: { room: room({ result: outcome, status: 'HAND_END' }), busy: false } }))
    expect(wrapper.findAll('.ym-fan-detail-list li strong').map(row => row.text())).toEqual(['平和', '门清', '不求人'])
    expect(wrapper.findAll('.ym-fan-detail-list li b').map(row => row.text())).toEqual(['1 番', '2 番', '1 番'])
    expect(wrapper.get('.ym-fan-calculation').text()).toContain('结算计番4 番')
    expect(wrapper.get('.ym-fan-breakdown').text()).not.toContain('断幺')
    expect(JSON.stringify(outcome)).toBe(before)
  })
})

const historicalCases: { label: string; items: Fan[] }[] = [
  { label: 'QINGQUANDAIYAO 4', items: [{ id: 'QINGQUANDAIYAO', name: '清全带幺', fan: 4, description: '旧版附录 B 计 4 番。' }] },
  { label: 'MENQING 1', items: [{ id: 'MENQING', name: '门清', fan: 1, description: '旧版门清。' }, { id: 'QINGYISE', name: '清一色', fan: 3, description: '旧版清一色。' }] },
  { label: 'ZIYISE + QINGYISE', items: [{ id: 'ZIYISE', name: '字一色', fan: 6, description: '旧版字一色。' }, { id: 'QINGYISE', name: '清一色', fan: 3, description: '旧版字牌仍加清一色。' }] },
  { label: 'DUANYAO 2', items: [{ id: 'DUANYAO', name: '断幺', fan: 2, description: '旧版断幺。' }, { id: 'QINGYISE', name: '清一色', fan: 3, description: '旧版清一色。' }] },
]

describe('historical results are not rescored using the new rulebook', () => {
  it.each(historicalCases)('keeps both settlement and replay snapshots unchanged: $label', async ({ items }) => {
    const currentRules = keep(mount(RulesPage, { props: { rules } }))
    expect(currentRules.findAll('.ym-fan-grid article strong').map(card => card.text())).toEqual(['1 番', '2 番', '3 番', '6 番'])
    const rawFan = items.reduce((sum, item) => sum + item.fan, 0)
    const outcome = result({ items, rawFan, fan: Math.min(8, rawFan) })
    const snapshot = JSON.stringify(outcome)
    const settlement = keep(mount(SettlementDialog, { props: { room: room({ result: outcome, status: 'HAND_END' }), busy: false } }))
    expect(settlement.findAll('.ym-fan-detail-list li b').map(row => row.text())).toEqual(items.map(item => `${item.fan} 番`))
    expect(settlement.get('.ym-fan-calculation').text()).toContain(`原始总番${rawFan} 番`)
    const record: HandRecord = { roomId: 'history', roomName: '历史牌局', round: 1, roundLabel: '东一局', startedAt: 1, completedAt: 2, complete: true, incomplete: false,
      frames: [{ index: 0, timestamp: 2, type: 'WIN', actorSeat: 0, message: '历史和牌', status: 'HAND_END', currentSeat: 0, wallCount: 20, dealerSeat: 0, players: [], lastDiscard: null, dice: null, result: outcome }], result: outcome }
    vi.spyOn(yaomingApi, 'get').mockImplementation(async path => ({ data: path === '/replays'
      ? { roomId: 'history', roomName: '历史牌局', hands: [{ round: 1, roundLabel: '东一局', startedAt: 1, completedAt: 2, frameCount: 1, incomplete: false, title: '历史和牌' }], note: '' }
      : record }))
    const post = vi.spyOn(yaomingApi, 'post')
    const replay = keep(mount(ReplayPage, { props: { identity: { roomId: 'history', playerId: 'p1', token: 'history-test-token' }, room: null, history: [] } }))
    await flushPromises()
    const replayResult = replay.get('.ym-replay-result')
    expect(replayResult.get('h3').text()).toContain(`${outcome.fan} 番`)
    for (const item of items) expect(replayResult.text()).toContain(`${item.name} ${item.fan}番`)
    expect(JSON.stringify(outcome)).toBe(snapshot)
    expect(post).not.toHaveBeenCalled()
  })
})

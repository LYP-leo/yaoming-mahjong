import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import YaomingApp from './YaomingApp.vue'
import RulesPage from './RulesPage.vue'
import RoomSidebar from './RoomSidebar.vue'
import TableView from './TableView.vue'
import WaitTiles from './WaitTiles.vue'
import { useYaomingStore, yaomingApi } from './store'
import { room, tile } from './testFixtures'
import { capacityOf, minimumFanOf, ruleIdOf, ruleNameOf, UnsupportedRuleError } from './ruleProfiles'
import type { RuleId, Rules } from './types'

const three: Rules = { id: 'yaoming-3p', name: '三人规则', playerCount: 3, tileCount: 108, totalRounds: 6,
  version: '26.9 LTS', description: '', notes: ['三人文件'], fans: [{ id: 'MENQING', name: '门清', fan: 2, description: '三人门清2番' }], tiles: [] }
const four: Rules = { ...three, id: 'yaoming-4p', name: '四人实验性规则', playerCount: 4, tileCount: 136, totalRounds: 8,
  notes: ['四人文件'], fans: [{ id: 'MENQING', name: '门清', fan: 1, description: '四人门清1番' },
    { id: 'QUANBUKAO', name: '全不靠', fan: 2, description: '全不靠加不求人合计3番，可以自摸和牌' }],
  tiles: [{ id: 'north', suit: 'HONORS', rank: 4, label: '北风' }, { id: 'two-man', suit: 'CHARACTERS', rank: 2, label: '二万' }] }
const wrappers: VueWrapper[] = []
const keep = <T extends VueWrapper>(wrapper: T) => { wrappers.push(wrapper); return wrapper }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(accept => { resolve = accept }); return { promise, resolve } }
function app() {
  const store = useYaomingStore()
  vi.spyOn(store, 'start').mockResolvedValue(); vi.spyOn(store, 'stop').mockImplementation(() => {})
  return { store, wrapper: keep(mount(YaomingApp, { attachTo: document.body })) }
}
beforeEach(() => { vi.restoreAllMocks(); localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia()) })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.restoreAllMocks() })

describe('named rule catalog and room creation', () => {
  it('defaults missing legacy metadata to three without guessing from the occupied seat count', () => {
    expect(capacityOf(room({ players: [] }))).toBe(3)
    expect(capacityOf(room({ capacity: 4, players: [] }))).toBe(3)
    expect(capacityOf(room({ ruleId: 'yaoming-4p', players: [] }))).toBe(4)
    expect(ruleIdOf(null)).toBe('yaoming-3p')
  })

  it('rejects unknown IDs explicitly rather than guessing three players or crashing on a missing name', () => {
    const unsupported = { ruleId: 'future-rules' as RuleId, capacity: 4, ruleName: '未来规则' }
    expect(() => ruleIdOf(unsupported)).toThrow(UnsupportedRuleError)
    expect(() => capacityOf(unsupported)).toThrow('当前版本不支持此规则')
    expect(() => ruleNameOf(unsupported)).toThrow(UnsupportedRuleError)
    expect(() => ruleNameOf({ ruleId: unsupported.ruleId })).toThrow('请更新页面后重试')
    expect(() => minimumFanOf(unsupported)).toThrow(UnsupportedRuleError)
  })

  it('rejects an unknown room profile at the read boundary and retains the last valid room', async () => {
    const identity = { roomId: 'room1', playerId: 'p1', token: 'known-profile-token' }
    const store = useYaomingStore(); store.identity = identity
    const current = room({ ruleId: 'yaoming-4p', capacity: 4, version: 2 }); store.room = current
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: room({ ruleId: 'unknown-room-profile' as RuleId, version: 3 }) })
    await store.refresh()
    expect(store.room).toEqual(current)
    expect(store.error).toContain('当前版本不支持此规则')
    expect(store.connected).toBe(false)
  })

  it('rejects an unknown lobby profile before rendering and retains the last valid list', async () => {
    const store = useYaomingStore()
    const existing = [{ id: 'known', name: '已有房间', status: 'WAITING', players: 2, capacity: 3, ruleId: 'yaoming-3p' as const }]
    store.rooms = existing
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: [...existing, { ...existing[0], id: 'unknown', ruleId: 'future-rule' }] })
    await store.loadLobby()
    expect(store.rooms).toEqual(existing)
    expect(store.error).toContain('当前版本不支持此规则')
    expect(store.connected).toBe(false)
  })

  it('accepts a legacy room without a rule ID as three players even if capacity says four', async () => {
    const store = useYaomingStore(); store.identity = { roomId: 'room1', playerId: 'p1', token: 'legacy-token' }
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: room({ capacity: 4 }) })
    await store.refresh()
    expect(store.connected).toBe(true)
    expect(capacityOf(store.room)).toBe(3)
  })

  it.each([undefined, 'yaoming-3p', 'yaoming-4p'] as const)('sends only an optional selected rule ID during creation: %s', async ruleId => {
    const identity = { roomId: 'room1', playerId: 'p1', token: 'private-create-token' }
    const post = vi.spyOn(yaomingApi, 'post').mockResolvedValue({ data: identity })
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: room({ ruleId, capacity: ruleId === 'yaoming-4p' ? 4 : 3 }) })
    const store = useYaomingStore()
    expect(await store.create('同桌', '玩家', ruleId)).toBe(true)
    expect(post).toHaveBeenCalledExactlyOnceWith('/rooms', { name: '同桌', playerName: '玩家', ...(ruleId ? { ruleId } : {}) })
  })

  it('discovers both rulesets without replacing the currently displayed detail', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: [three, four] })
    const store = useYaomingStore(); store.rules = four
    await store.loadRulesets()
    expect(get).toHaveBeenCalledExactlyOnceWith('/rulesets')
    expect(store.rulesets.map(rule => rule.id)).toEqual(['yaoming-3p', 'yaoming-4p'])
    expect(store.rules).toEqual(four)
  })

  it('cannot let a late three-player response overwrite the selected four-player catalog', async () => {
    const old = deferred<{ data: Rules }>(), fresh = deferred<{ data: Rules }>()
    const get = vi.spyOn(yaomingApi, 'get').mockReturnValueOnce(old.promise as never).mockReturnValueOnce(fresh.promise as never)
    const store = useYaomingStore(); store.rules = three
    const first = store.loadRules(), second = store.loadRules('yaoming-4p')
    expect(store.rules).toBeNull()
    fresh.resolve({ data: four }); await second
    old.resolve({ data: three }); await first
    expect(store.rules).toEqual(four)
    expect(get).toHaveBeenNthCalledWith(2, '/rules', { params: { ruleId: 'yaoming-4p' } })
  })

  it('preserves same-profile data while reloading but rejects a mismatched server catalog', async () => {
    const pending = deferred<{ data: Rules }>()
    vi.spyOn(yaomingApi, 'get').mockReturnValueOnce(pending.promise as never)
    const store = useYaomingStore(); store.rules = four
    const loading = store.loadRules('yaoming-4p')
    expect(store.rules).toEqual(four)
    pending.resolve({ data: three }); await loading
    expect(store.rules).toEqual(four)
    expect(store.error).not.toBe('')
  })

  it('restores the selected profile cache without briefly showing another profile', async () => {
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: four }).mockResolvedValueOnce({ data: three })
      .mockReturnValueOnce(new Promise(() => {}) as never)
    const store = useYaomingStore(); await store.loadRules('yaoming-4p'); await store.loadRules()
    void store.loadRules('yaoming-4p')
    expect(store.rules).toEqual(four)
  })
})

describe('separate rulebook and creation flow', () => {
  it.each([[three, 4], [four, 3]] as const)('keeps the profile minimum in rules/sidebar without adding a generic table fallback: $0.id', (rules, minimum) => {
    const value = room({ ruleId: rules.id, winHint: '' })
    expect(minimumFanOf(value)).toBe(minimum)
    const rulebook = keep(mount(RulesPage, { props: { rules } }))
    expect(rulebook.get('.ym-rule-overview').text()).toContain(`${minimum} 番起和`)
    expect(rulebook.text()).toContain(`点和仍须符合合法和牌结构并达到 ${minimum} 番`)
    const sidebar = keep(mount(RoomSidebar, { props: { room: value, identity: null } }))
    expect(sidebar.get('.ym-rule-mini').text()).toContain(`${minimum} 番起和 · 8 番封顶`)
    const table = keep(mount(TableView, { props: { room: value, busy: false } }))
    expect(table.find('.ym-win-hint').exists()).toBe(false)
    expect(table.get('.ym-panel-notices').text()).toBe('')
  })

  it('keeps legacy records at four fan and does not infer the minimum from capacity', () => {
    expect(minimumFanOf(null)).toBe(4)
    expect(minimumFanOf({ capacity: 4 })).toBe(4)
    expect(minimumFanOf({ ruleId: 'yaoming-4p', capacity: 3 })).toBe(3)
  })

  it('keeps authoritative server hints without a generic fallback when switching profiles', async () => {
    const fourRoom = room({ ruleId: 'yaoming-4p', winHint: '' })
    const table = keep(mount(TableView, { props: { room: fourRoom, busy: false } }))
    expect(table.find('.ym-win-hint').exists()).toBe(false)
    await table.setProps({ room: { ...fourRoom, winHint: '全不靠 · 不求人 · 3 番可自摸' } })
    expect(table.get('.ym-win-hint').text()).toBe('全不靠 · 不求人 · 3 番可自摸')
    await table.setProps({ room: room({ winHint: '' }) })
    expect(table.find('.ym-win-hint').exists()).toBe(false)
  })

  it('advertises the different thresholds clearly in the lobby', () => {
    const { wrapper } = app()
    expect(wrapper.get('.ym-hero-copy').text()).toContain('三人 108 张，4 番起和；四人 136 张，3 番起和')
  })

  it('shows a server-qualified three-fan wait without imposing a client-side four-fan filter', () => {
    const wrapper = keep(mount(WaitTiles, { props: { waits: [{ tile, unseenCount: 2, canTsumo: true, tsumoFan: 3, canRon: false, ronFan: 2, ronReason: '不足 3 番' }] } }))
    expect(wrapper.get('.ym-hint-fans').text()).toBe('自摸3番')
    expect(wrapper.get('.ym-hint-count').text()).toBe('2张')
    expect(wrapper.text()).not.toContain('不足')
  })

  it('shows authoritative four-player tiles/fans and correct eight-hand/four-times payment descriptions', () => {
    const wrapper = keep(mount(RulesPage, { props: { rules: four } }))
    expect(wrapper.text()).toContain('4 人对局'); expect(wrapper.text()).toContain('136 张牌')
    expect(wrapper.text()).toContain('东一至南四'); expect(wrapper.text()).toContain('4 倍番数')
    expect(wrapper.text()).toContain('另外三家各支付'); expect(wrapper.text()).toContain('点数总和始终为 40')
    expect(wrapper.text()).toContain('九万不能组成顺子')
    expect(wrapper.get('.ym-fan-grid').text()).toContain('门清1 番')
    expect(wrapper.get('.ym-fan-grid').text()).toContain('全不靠')
    expect(wrapper.findAll('.ym-tile-catalog .ym-tile')).toHaveLength(2)
    expect(wrapper.text()).not.toContain('东一至南三')
  })

  it('never labels a stale three-player fan list as the selected four-player rulebook', async () => {
    const wrapper = keep(mount(RulesPage, { props: { rules: three, ruleId: 'yaoming-4p' } }))
    expect(wrapper.findAll('.ym-fan-grid article')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('三人文件')
    expect(wrapper.text()).toContain('规则正在加载')
    await wrapper.get('[aria-label="选择规则手册"]').setValue('yaoming-3p')
    expect(wrapper.emitted('select')).toEqual([['yaoming-3p']])
  })

  it('lets a create dialog open selected details then return without losing its name or selection', async () => {
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: four })
    const { wrapper, store } = app(), create = vi.spyOn(store, 'create').mockResolvedValue(false)
    await wrapper.findAll('button').find(button => button.text().includes('创建牌局'))!.trigger('click')
    const inputs = wrapper.findAll('.ym-entry-dialog input')
    await inputs[0].setValue('北家'); await inputs[1].setValue('四人牌桌')
    await wrapper.get('[aria-label="选择牌局规则"]').setValue('yaoming-4p')
    await wrapper.findAll('.ym-entry-dialog button').find(button => button.text().includes('查看所选规则'))!.trigger('click')
    await flushPromises()
    expect(wrapper.find('form.ym-entry-dialog').exists()).toBe(false)
    expect(wrapper.get('.ym-rule-revision').text()).toBe('四人文件')
    await wrapper.findComponent(RulesPage).findAll('button').find(button => button.text().includes('返回'))!.trigger('click')
    expect(wrapper.findAll<HTMLInputElement>('.ym-entry-dialog input').map(input => input.element.value)).toEqual(['北家', '四人牌桌'])
    expect(wrapper.get<HTMLSelectElement>('[aria-label="选择牌局规则"]').element.value).toBe('yaoming-4p')
    await wrapper.get('form').trigger('submit')
    expect(create).toHaveBeenCalledExactlyOnceWith('四人牌桌', '北家', 'yaoming-4p')
  })

  it('uses each lobby room capacity and displays mixed named rules correctly', async () => {
    const { store, wrapper } = app()
    store.rooms = [
      { id: 'three', name: '三人已满', status: 'WAITING', players: 3, capacity: 3, ruleId: 'yaoming-3p', ruleName: '三人规则' },
      { id: 'four', name: '四人差一', status: 'WAITING', players: 3, capacity: 4, ruleId: 'yaoming-4p', ruleName: '四人实验性规则' },
    ]; await flushPromises()
    const cards = wrapper.findAll('.ym-room-list article')
    expect(cards.map(card => card.findAll('.ym-seat-dots i').length)).toEqual([3, 4])
    expect(cards[0].get('button').attributes('disabled')).toBeDefined()
    expect(cards[1].get('button').attributes('disabled')).toBeUndefined()
    expect(cards[1].text()).toContain('3 / 4 人'); expect(cards[1].text()).toContain('四人实验性规则')
  })

  it.each(['yaoming-3p', 'yaoming-4p'] as RuleId[])('opens the current room rule from its sidebar: %s', async ruleId => {
    const { store, wrapper } = app(), load = vi.spyOn(store, 'loadRules').mockResolvedValue()
    store.room = room({ ruleId }); await flushPromises()
    await wrapper.get('.ym-sidebar-rules').trigger('click')
    expect(load).toHaveBeenCalledWith(ruleId)
    expect(wrapper.get<HTMLSelectElement>('[aria-label="选择规则手册"]').element.value).toBe(ruleId)
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import TableView from './TableView.vue'
import { player, room, tile } from './testFixtures'
import type { Meld } from './types'

const wrappers: VueWrapper[] = []
const render = (view = room()) => {
  const wrapper = mount(TableView, { props: { room: view, busy: false } })
  wrappers.push(wrapper)
  return wrapper
}
const panelTracks = (wrapper: VueWrapper) => Array.from(wrapper.get('.ym-player-panel').element.children).map(element => element.className)
const meld = (prefix: string, concealed = false): Meld => ({
  type: 'KONG', tiles: Array.from({ length: 4 }, (_, i) => ({ ...tile, id: `${prefix}-${i}` })),
  fromSeat: 0, claimedTileId: `${prefix}-0`, concealed, added: !concealed,
})

beforeEach(() => localStorage.clear())
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals() })

describe('stable table content regions', () => {
  it('keeps the same six panel tracks for hints, long notices, trustee and legal action changes', async () => {
    const wrapper = render()
    const initial = panelTracks(wrapper)
    expect(initial).toEqual(['ym-own-info', 'ym-hand-row', 'ym-hand-hints', 'ym-panel-notices', 'ym-action-bar', 'ym-play-settings'])
    const longHint = '六字齐全不等于风龙，余牌须两顺子一雀头。'.repeat(30)
    const me = { ...player(), trustee: true, trusteeReason: 'TIMEOUT' as const }
    await wrapper.setProps({ room: room({ players: [me], winHint: longHint, actions: [{ type: 'TRUSTEE', label: '收回控制', tileIds: [] }] }), autoDrawNotice: '自动摸牌失败，请手动摸牌。'.repeat(10) })
    expect(panelTracks(wrapper)).toEqual(initial)
    expect(wrapper.get('.ym-panel-notices').text()).not.toContain(longHint)
    expect(wrapper.find('.ym-panel-notices .ym-win-hint').exists()).toBe(false)
    expect(wrapper.get('.ym-panel-notices .ym-trustee-banner').text()).toContain('操作超时')
    expect(wrapper.findAll('.ym-player-panel > .ym-trustee-banner')).toHaveLength(0)
    await wrapper.get('.ym-trustee-banner button').trigger('click')
    expect(wrapper.emitted('action')?.[0][0]).toEqual({ type: 'TRUSTEE', label: '收回控制', tileIds: [] })
    await wrapper.setProps({ room: room({ status: 'NEED_DRAW', winHint: '', actions: [{ type: 'DRAW', label: '摸牌', tileIds: [] }] }), autoDrawNotice: '' })
    expect(panelTracks(wrapper)).toEqual(initial)
    expect(wrapper.get('.ym-panel-notices').text()).toBe('')
  })

  it('keeps three stable lanes and previews the latest 24 discards without duplicating the own river', async () => {
    const wrapper = render()
    const stableContainers = [wrapper.get('.ym-table').element, ...wrapper.findAll('.ym-player-lane').map(lane => lane.element), wrapper.get('.ym-player-panel').element]
    expect(wrapper.findAll('.ym-table > .ym-player-lane')).toHaveLength(3)
    expect(wrapper.findAll('.ym-river .ym-tile')).toHaveLength(0)
    const players = room().players.map(p => ({ ...p, discards: Array.from({ length: 60 }, (_, i) => ({ ...tile, id: `${p.id}-river-${i}` })) }))
    await wrapper.setProps({ room: room({ players, version: 2, lastDiscard: { tile: players[1].discards[59], fromSeat: 1, claimed: false } }) })
    expect(wrapper.findAll('.ym-river .ym-tile')).toHaveLength(72)
    expect(wrapper.findAll('.ym-lane-self .ym-river .ym-tile')).toHaveLength(24)
    for (const lane of wrapper.findAll('.ym-player-lane')) {
      const tiles = lane.findAll('.ym-lane-river-tile')
      expect(tiles).toHaveLength(24)
      expect(tiles.map(item => Number(item.attributes('data-discard-index')))).toEqual(Array.from({ length: 24 }, (_, i) => 37 + i))
      expect(lane.findAll('.ym-lane-river-mobile-hidden')).toHaveLength(8)
    }
    expect(wrapper.findAll('.ym-latest-river-tile')).toHaveLength(1)
    expect(wrapper.findAll('.ym-player-panel .ym-river')).toHaveLength(0)
    expect([wrapper.get('.ym-table').element, ...wrapper.findAll('.ym-player-lane').map(lane => lane.element), wrapper.get('.ym-player-panel').element]).toEqual(stableContainers)
    expect(wrapper.find('.ym-opponents, .ym-own-table-zone, .ym-table-center').exists()).toBe(false)
  })

  it('keeps actual hand, notice and action overflow regions reachable by keyboard', () => {
    const wrapper = render()
    const regions = wrapper.findAll('.ym-hand-scroll, .ym-panel-notices, .ym-action-bar')
    expect(regions).toHaveLength(3)
    regions.forEach(region => { expect(region.attributes('tabindex')).toBe('0'); expect(region.attributes('aria-label')).toBeTruthy() })
  })

  it('opens the complete ordered river explicitly and closes it without rebuilding the public lane', async () => {
    const players = room().players
    players[1].discards = Array.from({ length: 60 }, (_, i) => ({ ...tile, id: `full-river-${i}` }))
    players[1].discardKinds = { 'full-river-59': 'TSUMOGIRI' }
    const wrapper = render(room({ players, lastDiscard: { tile: players[1].discards[59], fromSeat: 1, claimed: false } }))
    const lane = wrapper.get('[data-player-id="p2"]')
    const originalLane = lane.element
    const opener = lane.get('.ym-lane-river-more')
    expect(opener.attributes('aria-label')).toContain('共 60 张')
    await opener.trigger('click')
    const dialog = wrapper.get('.ym-river-dialog')
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(dialog.findAll('.ym-full-river .ym-discard-tile')).toHaveLength(60)
    expect(dialog.findAll('.ym-full-river li > small').map(label => Number(label.text()))).toEqual(Array.from({ length: 60 }, (_, i) => i + 1))
    expect(dialog.findAll('.ym-latest-river-tile')).toHaveLength(1)
    expect(dialog.findAll('.ym-discard-tsumogiri')).toHaveLength(1)
    await dialog.get('[aria-label="关闭完整牌河"]').trigger('click')
    expect(wrapper.find('.ym-river-dialog').exists()).toBe(false)
    expect(wrapper.get('[data-player-id="p2"]').element).toBe(originalLane)
    expect(lane.findAll('.ym-river .ym-discard-tile')).toHaveLength(24)
    await opener.trigger('click')
    await wrapper.get('.ym-river-dialog').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.ym-river-dialog').exists()).toBe(false)
  })

  it('keeps added melds and concealed-kong privacy inside separate meld containers', () => {
    const players = room().players
    players[1].hand = [{ ...tile, id: 'secret-tile', suit: 'HONORS', rank: 5, label: '绝不能公开的暗手牌' }]
    players[1].melds = [meld('added'), meld('hidden', true)]
    const wrapper = render(room({ players }))
    const opponent = wrapper.get('[aria-label="玩家2 的区域"]')
    expect(opponent.get('.ym-meld-strip').findAll('.ym-meld')).toHaveLength(2)
    expect(opponent.get('.ym-meld-strip').findAll('.ym-meld-stack')).toHaveLength(1)
    expect(opponent.get('.ym-meld-strip').findAll('.ym-tile-back')).toHaveLength(2)
    expect(opponent.get('.ym-concealed-hand').findAll('.ym-tile-back')).toHaveLength(13)
    expect(wrapper.html()).not.toContain('绝不能公开的暗手牌')
    expect(wrapper.html()).not.toContain('secret-tile')
    expect(opponent.findAll('.ym-river .ym-meld')).toHaveLength(0)
  })

  it('shows public hand-size backs in all three lanes including self, with real faces only in the bottom panel', () => {
    const wrapper = render()
    wrapper.findAll('.ym-player-lane').forEach(lane => expect(lane.findAll('.ym-concealed-hand .ym-tile-back')).toHaveLength(13))
    expect(wrapper.findAll('.ym-lane-self')).toHaveLength(1)
    expect(wrapper.findAll('.ym-table .ym-hand-tile')).toHaveLength(0)
    expect(wrapper.findAll('.ym-player-panel .ym-hand-tile')).toHaveLength(1)
    expect(wrapper.find('.ym-opponent-toggle').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('手牌在下方')
    expect(wrapper.text()).not.toContain('场上玩家 3')
  })

  it('orders lanes by current east-south-west winds while the self marker remains independent of the active seat', async () => {
    const wrapper = render(room({ currentSeat: 2 }))
    const names = () => wrapper.findAll('.ym-player-lane').map(lane => lane.get('.ym-lane-identity').text())
    names().forEach((name, i) => expect(name).toContain(`玩家${i + 1}`))
    expect(wrapper.get('.ym-lane-self .ym-lane-identity').text()).toContain('玩家1')
    const players = room().players.map(p => ({ ...p, wind: ['东', '南', '西'][(p.seat - 2 + 3) % 3] }))
    await wrapper.setProps({ room: room({ players, dealerSeat: 2, currentSeat: 0, meId: 'p2' }) })
    names().forEach((name, i) => expect(name).toContain(['玩家3', '玩家1', '玩家2'][i]))
    expect(wrapper.get('.ym-lane-self .ym-lane-identity').text()).toContain('玩家2')
    await wrapper.setProps({ room: room({ players, dealerSeat: 2, currentSeat: 1, meId: 'p2' }) })
    names().forEach((name, i) => expect(name).toContain(['玩家3', '玩家1', '玩家2'][i]))
    expect(wrapper.findAll('.ym-lane-self')).toHaveLength(1)
  })

  it('keeps an empty dedicated hints slot and reuses it across ordinary room updates', async () => {
    const wrapper = mount(TableView, { props: { room: room(), busy: false }, slots: { hints: '<div class="hint-slot-probe">4番</div>' } })
    wrappers.push(wrapper)
    const slot = wrapper.get('.ym-hand-hints').element
    const content = wrapper.get('.hint-slot-probe').element
    expect(wrapper.get('.ym-hand-hints').text()).toBe('4番')
    await wrapper.setProps({ room: room({ version: 3, currentSeat: 2 }) })
    expect(wrapper.get('.ym-hand-hints').element).toBe(slot)
    expect(wrapper.get('.hint-slot-probe').element).toBe(content)
    expect(render().get('.ym-hand-hints').text()).toBe('')
  })

  it('retains every legal action and emits the exact server choice from a long action strip', async () => {
    const actions = Array.from({ length: 16 }, (_, i) => ({ type: 'KONG', label: `杠牌选择 ${i + 1}`, tileIds: ['t1'] }))
    const wrapper = render(room({ status: 'REACTION', actions }))
    const buttons = wrapper.findAll('.ym-action-bar button')
    expect(buttons).toHaveLength(16)
    await buttons[15].trigger('click')
    expect(wrapper.emitted('action')?.[0][0]).toEqual(actions[15])
    await wrapper.setProps({ busy: true })
    await buttons[15].trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
  })

  it('observes the panel height for mobile clearance and disconnects on unmount', () => {
    let resize: ResizeObserverCallback = () => undefined
    const observe = vi.fn(), disconnect = vi.fn()
    vi.stubGlobal('ResizeObserver', class { constructor(callback: ResizeObserverCallback) { resize = callback } observe = observe; disconnect = disconnect })
    const wrapper = render()
    const panel = wrapper.get('.ym-player-panel').element
    vi.spyOn(panel, 'getBoundingClientRect').mockReturnValue({ height: 319 } as DOMRect)
    resize([], {} as ResizeObserver)
    expect(observe).toHaveBeenCalledWith(panel)
    expect(wrapper.emitted('panelSize')).toEqual([[319]])
    wrapper.unmount(); wrappers.pop()
    expect(disconnect).toHaveBeenCalledOnce()
  })
})

import { afterEach, describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount, type VueWrapper } from '@vue/test-utils'
import SettlementDialog from './SettlementDialog.vue'
import TileView from './TileView.vue'
import MeldView from './MeldView.vue'
import { player, result, room } from './testFixtures'
import type { Meld, Result, RoomView, Tile } from './types'

const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })
const tiles = (count: number, prefix = 'winning'): Tile[] => Array.from({ length: count }, (_, index) => ({ id: `${prefix}-${index}`, suit: 'BAMBOO', rank: index % 9 + 1, label: `${index % 9 + 1}条` }))
function finished(overrides: Partial<Result> = {}, roomOverrides: Partial<RoomView> = {}) {
  const hand = tiles(14)
  return room({ status: 'HAND_END', currentSeat: -1, actions: [{ type: 'ACK', label: '确认', tileIds: [] }],
    result: result({ winnerId: 'p2', title: '乙 自摸', winningTile: hand.at(-1)!,
      hands: [{ playerId: 'p1', hand: tiles(13, 'opponent-a'), melds: [] }, { playerId: 'p2', hand, melds: [] }, { playerId: 'p3', hand: tiles(13, 'opponent-c'), melds: [] }], ...overrides }),
    ...roomOverrides })
}
function show(view = finished(), busy = false) {
  const wrapper = mount(SettlementDialog, { props: { room: view, busy } })
  wrappers.push(wrapper); return wrapper
}
const winnerIds = (wrapper: VueWrapper) => wrapper.findAll('.ym-winning-hand [data-tile-id]').map(tile => tile.attributes('data-tile-id'))

describe('explainable settlement', () => {
  it('shows every authoritative winning hand tile by default instead of only the last tile', () => {
    const view = finished(), wrapper = show(view)
    const expected = view.result!.hands.find(hand => hand.playerId === 'p2')!.hand
    expect(winnerIds(wrapper)).toEqual(expected.map(tile => tile.id))
    expect(wrapper.find('.ym-winning-tiles').element.closest('details')).toBeNull()
    expect(wrapper.find('.ym-winning-tiles').text()).toContain('手牌 · 14 张')
    expect(wrapper.find('.ym-winning-melds').text()).toContain('无副露')
    expect(wrapper.findAll('[data-winning-tile="true"]')).toHaveLength(1)
    expect(wrapper.find('[data-winning-tile="true"]').attributes('data-tile-id')).toBe(view.result!.winningTile!.id)
    expect(wrapper.find('.ym-result-winner').findAllComponents(TileView)).toHaveLength(0)
  })

  it('uses physical ID for the highlight when the same tile type appears more than once', () => {
    const hand = tiles(14).map(tile => ({ ...tile, rank: 3, label: '三条' }))
    const wrapper = show(finished({ winningTile: { ...hand[4] }, hands: [{ playerId: 'p2', hand, melds: [] }] }))
    expect(wrapper.findAll('[data-winning-tile="true"]')).toHaveLength(1)
    expect(wrapper.find('[data-winning-tile="true"]').attributes('data-tile-id')).toBe(hand[4].id)
    expect(wrapper.find('[data-winning-tile="true"]').attributes('aria-label')).toBe('三条，和牌张')
    expect(winnerIds(wrapper)).toHaveLength(14)
  })

  it('does not append or duplicate a ron tile already transferred into the winner hand', () => {
    const view = finished({ title: '乙 点和' })
    const before = JSON.stringify(view.result), wrapper = show(view)
    const winningId = view.result!.winningTile!.id
    const rendered = wrapper.findAllComponents(TileView).map(component => component.props('tile')?.id)
    expect(rendered.filter(id => id === winningId)).toHaveLength(1)
    expect(winnerIds(wrapper)).toHaveLength(14)
    expect(JSON.stringify(view.result)).toBe(before)
    expect(wrapper.find('.ym-result-hands').findAll('article')).toHaveLength(2)
  })

  it.each(['p1', 'p2', 'p3'])('shows the same complete winning hand to participant %s without relying on their cropped live view', meId => {
    const view = finished({}, { meId, players: [player(), player('p2', 1), player('p3', 2)].map(p => ({ ...p, hand: p.id === meId ? tiles(1, 'not-the-result') : [] })) })
    const wrapper = show(view)
    expect(winnerIds(wrapper)).toEqual(view.result!.hands[1].hand.map(tile => tile.id))
    expect(winnerIds(wrapper)).not.toContain('not-the-result-0')
    expect(wrapper.find('.ym-result-winner').text()).toContain('乙')
    expect(wrapper.find('.ym-fan-breakdown').text()).toContain('清四连')
  })

  it.each([
    { name: '碰', type: 'PONG', concealed: false, added: false, count: 3 },
    { name: '明杠', type: 'KONG', concealed: false, added: false, count: 4 },
    { name: '暗杠', type: 'KONG', concealed: true, added: false, count: 4 },
    { name: '加杠', type: 'KONG', concealed: false, added: true, count: 4 },
  ])('shows the full $name plus remaining concealed hand without hiding settlement tiles', ({ name, type, concealed, added, count }) => {
    const hand = tiles(11), declared = tiles(count, 'declared').map(tile => ({ ...tile, rank: 7, label: '七条' }))
    const meld: Meld = { type: type as Meld['type'], concealed, added, fromSeat: concealed ? 1 : 0, claimedTileId: concealed ? '' : declared[0].id, tiles: declared }
    const wrapper = show(finished({ winningTile: hand.at(-1)!, hands: [{ playerId: 'p2', hand, melds: [meld] }] }))
    expect(wrapper.findAllComponents(TileView)).toHaveLength(11 + count)
    expect(wrapper.find('.ym-winning-melds').text()).toContain(name)
    expect(wrapper.findAll('.ym-tile-back')).toHaveLength(0)
    expect(wrapper.findComponent(MeldView).props('reveal')).toBe(true)
    expect(wrapper.findComponent(MeldView).props('ownerSeat')).toBe(1)
    const ids = wrapper.findAllComponents(TileView).map(component => component.props('tile')?.id)
    expect(new Set(ids).size).toBe(11 + count)
    expect(wrapper.findAll('[data-winning-tile="true"]')).toHaveLength(1)
  })

  it('shows every server fan and its full explanation next to the hand without a hover or expansion', () => {
    const items = [{ id: 'MENQING', name: '门清', fan: 2, description: '没有吃、碰、明杠或加杠；允许暗杠。' },
      { id: 'QINGYISE', name: '清一色', fan: 3, description: '只有一种数牌花色，不含字牌；不计混一色。' }]
    const wrapper = show(finished({ items, rawFan: 5, fan: 5 }))
    const rows = wrapper.findAll('.ym-fan-detail-list li')
    expect(rows).toHaveLength(items.length)
    items.forEach((item, index) => {
      expect(rows[index].text()).toContain(item.name)
      expect(rows[index].find('b').text()).toBe(`${item.fan} 番`)
      expect(rows[index].find('p').text()).toBe(item.description)
      expect(rows[index].element.closest('details')).toBeNull()
    })
    expect(wrapper.find('.ym-fan-calculation').text()).toContain('原始总番5 番')
    expect(wrapper.find('.ym-fan-calculation').text()).toContain('结算计番5 番')
    expect(wrapper.find('.ym-fan-calculation').text()).toContain('未触发 8 番封顶')
  })

  it('displays the server raw total and capped total rather than recalculating from incomplete fan details', () => {
    const wrapper = show(finished({ rawFan: 11, fan: 8, items: [{ id: 'ZIYISE', name: '字一色', fan: 6, description: '只有字牌。' }] }))
    expect(wrapper.find('.ym-fan-calculation').text()).toContain('原始总番11 番')
    expect(wrapper.find('.ym-fan-calculation').text()).toContain('封顶后计番8 番')
    expect(wrapper.find('.ym-fan-calculation').text()).toContain('8 番封顶')
    expect(wrapper.find('.ym-fan-total').text()).toContain('8 番')
    expect(wrapper.find('.ym-fan-authority').text()).toContain('不重新计算')
  })

  it('keeps immutable score and payment names after another player leaves the live room', () => {
    const wrapper = show(finished({}, { players: [player()], meId: 'p1' }))
    expect(wrapper.find('.ym-result-winner').text()).toContain('乙')
    expect(wrapper.find('.ym-result-scores').text()).toContain('丙')
    expect(wrapper.find('.ym-payment').text()).toContain('乙 → 甲')
    expect(winnerIds(wrapper)).toHaveLength(14)
  })

  it('renders draws without inventing a winner or showing a winning-tile panel', () => {
    const wrapper = show(finished({ draw: true, title: '荒牌流局', winnerId: null, winningTile: null, rawFan: 0, fan: 0, items: [], payments: [] }))
    expect(wrapper.find('.ym-draw-summary').text()).toContain('没有和牌玩家')
    expect(wrapper.find('.ym-winning-analysis').exists()).toBe(false)
    expect(wrapper.findAll('[data-winning-tile="true"]')).toHaveLength(0)
    expect(wrapper.find('.ym-result-hands summary').text()).toBe('查看三家终局手牌')
    expect(wrapper.find('.ym-result-hands').findAll('article')).toHaveLength(3)
    expect(wrapper.findAll('.ym-result-scores article')).toHaveLength(3)
  })

  it('explains absent winner-hand data and does not create a hand from the lone winning tile', () => {
    const wrapper = show(finished({ hands: [] }))
    expect(wrapper.find('.ym-winning-tiles').text()).toContain('未记录赢家完整手牌')
    expect(wrapper.findAllComponents(TileView)).toHaveLength(0)
    expect(wrapper.find('.ym-fan-breakdown').text()).toContain('4 番')
  })

  it('handles absent winner and legacy missing arrays without crashing or inventing data', () => {
    const legacy = result({ winnerId: null, hands: undefined, items: undefined, payments: undefined, scores: undefined } as unknown as Partial<Result>)
    const wrapper = show(finished(legacy))
    expect(wrapper.find('.ym-result-winner').text()).toContain('未记录赢家')
    expect(wrapper.find('.ym-winning-tiles').text()).toContain('未记录赢家完整手牌')
    expect(wrapper.find('.ym-fan-breakdown').text()).toContain('未附番型明细')
    expect(wrapper.findAllComponents(TileView)).toHaveLength(0)
  })

  it('leaves an unlocated winning tile unhighlighted and never adds it to an incomplete recorded hand', () => {
    const wrapper = show(finished({ winningTile: { id: 'missing-winner-tile', suit: 'DOTS', rank: 9, label: '九筒' } }))
    expect(winnerIds(wrapper)).toHaveLength(14)
    expect(wrapper.findAll('[data-winning-tile="true"]')).toHaveLength(0)
    expect(wrapper.find('.ym-winning-tiles').text()).toContain('未补加任何牌')
    expect(wrapper.findAllComponents(TileView).some(component => component.props('tile')?.id === 'missing-winner-tile')).toBe(false)
  })

  it('retains explicit ACK, LEAVE and busy behavior and emits no automatic action on render', async () => {
    const wrapper = show()
    expect(wrapper.emitted('action')).toBeUndefined()
    await wrapper.find('.ym-result-footer button').trigger('click')
    expect(wrapper.emitted('action')?.[0][0]).toEqual({ type: 'ACK', label: '确认', tileIds: [] })
    const leave = { type: 'LEAVE', label: '退出', tileIds: [] }
    await wrapper.setProps({ room: finished({ matchOver: true }, { actions: [leave] }), busy: true })
    expect(wrapper.find('.ym-result-footer button').attributes('disabled')).toBeDefined()
    await wrapper.find('.ym-result-footer button').trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
    await wrapper.setProps({ busy: false }); await wrapper.find('.ym-result-footer button').trigger('click')
    expect(wrapper.emitted('action')?.[1][0]).toEqual(leave)
    expect(wrapper.find('.ym-result-footer').text()).toContain('不会自动退出')
    await wrapper.find('[aria-label="暂时收起结算"]').trigger('click')
    expect(wrapper.emitted('minimize')).toHaveLength(1)
  })

  it('keeps confirmation outside the scrollable detail area and provides a keyboard-scrollable hand', () => {
    const wrapper = show()
    expect(wrapper.find('.ym-result-footer').element.closest('.ym-result-content')).toBeNull()
    expect(wrapper.find('.ym-winning-hand').attributes('tabindex')).toBe('0')
    expect(wrapper.find('.ym-result-content').attributes('tabindex')).toBe('0')
  })

  it('keeps the settlement heading and hand proportions correct with the application baseline', () => {
    const baseStyle = document.createElement('style'), settlementStyle = document.createElement('style'), host = document.createElement('div')
    baseStyle.textContent = readFileSync(resolve(process.cwd(), 'src/base.css'), 'utf8')
    settlementStyle.textContent = readFileSync(resolve(process.cwd(), 'src/yaoming/settlement.css'), 'utf8')
    host.className = 'ym-app'; document.body.append(host); document.head.append(baseStyle, settlementStyle)
    const wrapper = mount(SettlementDialog, { props: { room: finished(), busy: false }, attachTo: host })
    try {
      const style = getComputedStyle(wrapper.find('.ym-result-heading').element)
      expect(style.display).toBe('block'); expect(style.height).toBe('auto'); expect(style.margin).toBe('0px')
      expect(style.maxWidth).toBe('none')
      expect(getComputedStyle(wrapper.find('.ym-winning-analysis').element).gridTemplateColumns).toBe('minmax(0, 1.8fr) minmax(0, 1fr)')
    } finally { wrapper.unmount(); host.remove(); baseStyle.remove(); settlementStyle.remove() }
  })

  it('handles a temporarily absent result without offering a speculative confirmation', () => {
    const wrapper = show(room({ result: null, actions: [{ type: 'ACK', label: '确认', tileIds: [] }] }))
    expect(wrapper.find('#ym-result-title').text()).toBe('结算同步中')
    expect(wrapper.find('.ym-result-footer button').attributes('disabled')).toBeDefined()
    expect(wrapper.emitted('action')).toBeUndefined()
    expect(wrapper.findAllComponents(TileView)).toHaveLength(0)
  })
})

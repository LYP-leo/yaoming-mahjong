import { afterEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import RulesPage from './RulesPage.vue'
import SettlementDialog from './SettlementDialog.vue'
import { result, room } from './testFixtures'
import type { Fan, Result, Rules, Tile } from './types'

const wrappers: VueWrapper[] = []
const keep = <T extends VueWrapper>(wrapper: T): T => { wrappers.push(wrapper); return wrapper }
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })

const nineRanks: Fan = {
  id: 'JIUSHUQI', name: '九数齐', fan: 4,
  description: '只含数牌，不得含字牌；包含 1 至 9，每种数值只能出现在一个面子或雀头中。',
}
const concealed: Fan = { id: 'MENQING', name: '门清', fan: 2, description: '没有吃、碰、明杠或加杠；允许暗杠。' }
const rules: Rules = { name: '要命麻将', version: 'test', description: '', notes: [], tiles: [], fans: [concealed, nineRanks] }
const hand = (codes: string[]): Tile[] => codes.map((code, index) => {
  const rank = Number(code.slice(1)), suit = code[0] === 'H' ? 'HONORS' : 'BAMBOO'
  return { id: `nine-ranks-${index}`, suit, rank, label: suit === 'HONORS' ? '东' : `${rank}条` }
})
const numberedHand = hand(['B1', 'B2', 'B3', 'B4', 'B5', 'B6', 'B7', 'B7', 'B7', 'B8', 'B8', 'B8', 'B9', 'B9'])
const honorsHand = hand(['B1', 'B2', 'B3', 'B4', 'B5', 'B6', 'B7', 'B8', 'B9', 'H1', 'H1', 'H1', 'B5', 'B5'])
function showSettlement(tiles: Tile[], overrides: Partial<Result>) {
  const serverResult = result({ hands: [{ playerId: 'p1', hand: tiles, melds: [] }], winningTile: tiles.at(-1)!, ...overrides })
  const snapshot = JSON.stringify(serverResult)
  const wrapper = keep(mount(SettlementDialog, { props: { room: room({ status: 'HAND_END', result: serverResult }), busy: false } }))
  expect(JSON.stringify(serverResult)).toBe(snapshot)
  expect(wrapper.findAll('.ym-winning-hand [data-tile-id]')).toHaveLength(tiles.length)
  return wrapper
}

describe('nine-ranks server rule presentation', () => {
  it('renders the complete server JIUSHUQI description after rules load, without supplying a local rule', async () => {
    const wrapper = keep(mount(RulesPage, { props: { rules: null } }))
    expect(wrapper.findAll('.ym-fan-grid article')).toHaveLength(0)
    await wrapper.setProps({ rules })
    const card = wrapper.findAll('.ym-fan-grid article').find(article => article.get('h3').text() === '九数齐')!
    expect(card.get('p').text()).toBe(nineRanks.description)
    expect(card.text()).toContain('只含数牌')
    expect(card.text()).toContain('不得含字牌')
    expect(card.get('strong').text()).toBe('4 番')
    expect(wrapper.findAll('.ym-rule-overview article')).toHaveLength(4)
    expect(wrapper.findAll('.ym-rule-section')).toHaveLength(4)
  })

  it.each(['九数齐', '不得含字牌'])('finds the server rule by name or its corrected condition: %s', async query => {
    const wrapper = keep(mount(RulesPage, { props: { rules } }))
    await wrapper.get('[aria-label="搜索番种"]').setValue(` ${query} `)
    const cards = wrapper.findAll('.ym-fan-grid article')
    expect(cards).toHaveLength(1)
    expect(cards[0].get('h3').text()).toBe('九数齐')
    expect(cards[0].get('p').text()).toBe(nineRanks.description)
    await wrapper.get('[aria-label="搜索番种"]').setValue('')
    expect(wrapper.findAll('.ym-fan-grid article')).toHaveLength(2)
  })
})

describe('nine-ranks server-authoritative settlement', () => {
  it('shows JIUSHUQI only as the server supplied it, retaining server totals rather than adding the four fan again', () => {
    const wrapper = showSettlement(numberedHand, { items: [nineRanks], rawFan: 6, fan: 6 })
    const rows = wrapper.findAll('.ym-fan-detail-list li')
    expect(rows).toHaveLength(1)
    expect(rows[0].get('strong').text()).toBe('九数齐')
    expect(rows[0].get('b').text()).toBe('4 番')
    expect(rows[0].get('p').text()).toBe(nineRanks.description)
    expect(wrapper.get('.ym-fan-calculation').text()).toContain('原始总番6 番')
    expect(wrapper.get('.ym-fan-calculation').text()).toContain('结算计番6 番')
  })

  it('does not invent JIUSHUQI from ranks 1–9 when the server hand contains honors and other eligible fan', () => {
    const items: Fan[] = [concealed,
      { id: 'BUQIUREN', name: '不求人', fan: 1, description: '门清自摸成和。' },
      { id: 'HUNYISE', name: '混一色', fan: 2, description: '恰有一种数牌花色和字牌。' }]
    const wrapper = showSettlement(honorsHand, { items, rawFan: 5, fan: 5 })
    expect(wrapper.findAll('.ym-fan-detail-list li strong').map(row => row.text())).toEqual(items.map(item => item.name))
    expect(wrapper.get('.ym-fan-breakdown').text()).not.toContain('九数齐')
    expect(wrapper.get('.ym-fan-calculation').text()).toContain('结算计番5 番')
  })

  it('does not infer JIUSHUQI even from a numbered hand when an older result has no fan details', () => {
    const wrapper = showSettlement(numberedHand, { items: [], rawFan: 4, fan: 4 })
    expect(wrapper.findAll('.ym-fan-detail-list li')).toHaveLength(0)
    expect(wrapper.get('.ym-fan-breakdown').text()).toContain('未附番型明细，不在前端推算补齐')
    expect(wrapper.get('.ym-fan-breakdown').text()).not.toContain('九数齐')
    expect(wrapper.get('.ym-fan-calculation').text()).toContain('结算计番4 番')
  })
})

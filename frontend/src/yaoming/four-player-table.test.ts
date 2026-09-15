import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import TableView from './TableView.vue'
import MeldView from './MeldView.vue'
import RoomSidebar from './RoomSidebar.vue'
import SettlementDialog from './SettlementDialog.vue'
import StableTablePreview from './dev/StableTablePreview.vue'
import { player, room, result } from './testFixtures'
import { windAt } from './ruleProfiles'
import type { RoomView, Tile } from './types'

const wrappers: VueWrapper[] = [], elements: HTMLElement[] = []
const keep = <T extends VueWrapper>(wrapper: T) => { wrappers.push(wrapper); return wrapper }
const tiles = (seat: number, size = 13): Tile[] => Array.from({ length: size }, (_, index) => ({ id: `seat-${seat}-${index}`, suit: 'BAMBOO', rank: index % 9 + 1, label: `${index % 9 + 1}条` }))
function four(self = 0, dealer = 0): RoomView {
  const players = Array.from({ length: 4 }, (_, seat) => ({ ...player(`p${seat + 1}`, seat), wind: windAt(seat, dealer, 4),
    hand: tiles(seat, seat === self ? 14 : 13), handSize: seat === self ? 14 : 13, drawnTileId: seat === self ? `seat-${seat}-13` : null }))
  return room({ ruleId: 'yaoming-4p', ruleName: '四人实验性规则', capacity: 4, players, meId: players[self].id,
    dealerSeat: dealer, currentSeat: self, actions: players[self].hand.map(tile => ({ type: 'DISCARD', label: `打出${tile.label}`, tileIds: [tile.id] })) })
}
function table(view = four()) {
  const host = document.createElement('div'); host.className = 'ym-app'; document.body.append(host); elements.push(host)
  return keep(mount(TableView, { props: { room: view, busy: false }, attachTo: host }))
}
async function key(value: string) { document.dispatchEvent(new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true })); await nextTick() }
beforeEach(() => { vi.useFakeTimers(); localStorage.clear() })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); elements.splice(0).forEach(element => element.remove()); vi.clearAllTimers(); vi.useRealTimers() })

describe('fixed four-player public lanes and unchanged private controls', () => {
  it.each(Array.from({ length: 16 }, (_, index) => [index % 4, Math.floor(index / 4)]))('fixes East/South/West/North with self seat %s and dealer seat %s', (self, dealer) => {
    const value = four(self, dealer), wrapper = table(value), lanes = wrapper.findAll('.ym-player-lane')
    expect(lanes.map(lane => lane.attributes('data-wind'))).toEqual(['东', '南', '西', '北'])
    expect(wrapper.findAll('.ym-lane-self')).toHaveLength(1)
    expect(wrapper.get('.ym-lane-self').attributes('data-player-id')).toBe(value.meId)
    expect(wrapper.findAll('.ym-lane-hand-slot')).toHaveLength(56)
    expect(wrapper.findAll('.ym-lanes-table .mahjong-art')).toHaveLength(0)
    expect(wrapper.findAll('.ym-lanes-table .ym-tile-back')).toHaveLength(53)
    expect(wrapper.get('.ym-hand-tile-selected').attributes('data-hand-tile-id')).toBe(`seat-${self}-13`)
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('reserves all four lanes in an only-one-player waiting room', () => {
    const value = four(3), wrapper = table({ ...value, players: [value.players[3]], status: 'WAITING', actions: [] })
    expect(wrapper.get('.ym-lanes-table').attributes('aria-label')).toBe('四人麻将牌桌')
    expect(wrapper.findAll('.ym-lane-empty')).toHaveLength(3)
    expect(wrapper.get('.ym-player-lane').attributes('data-wind')).toBe('北')
    expect(wrapper.get('.ym-lanes-table').classes()).toContain('ym-four-player-table')
  })

  it.each([' ', 'Enter'])('lets North confirm the default physical draw with %j while a later reaction Space remains PASS', async confirm => {
    const value = four(3), wrapper = table(value)
    await key(confirm)
    expect(wrapper.emitted('action')).toEqual([[value.actions[13]]])
    const pass = { type: 'PASS', label: '过', tileIds: [] }
    await wrapper.setProps({ room: { ...value, version: 2, status: 'REACTION', currentSeat: 0, actions: [pass] } })
    await key(' ')
    expect(wrapper.emitted('action')).toEqual([[value.actions[13]], [pass]])
  })

  it('keeps every slot, score, meld anchor and the private hand panel across public-state updates', async () => {
    const value = four(3), wrapper = table(value)
    const slots = wrapper.findAll('.ym-lane-hand-slot').map(node => node.element), scores = wrapper.findAll('.ym-lane-score').map(node => node.element)
    const melds = wrapper.findAll('.ym-meld-strip').map(node => node.element), panel = wrapper.get('.ym-player-panel').element
    await wrapper.setProps({ room: { ...value, version: 2, currentSeat: 1, message: '其他玩家行动', players: value.players.map(p => ({ ...p, handSize: 13, hand: p.hand.slice(0, 13), drawnTileId: null })) } })
    expect(wrapper.findAll('.ym-lane-hand-slot').map(node => node.element)).toEqual(slots)
    expect(wrapper.findAll('.ym-lane-score').map(node => node.element)).toEqual(scores)
    expect(wrapper.findAll('.ym-meld-strip').map(node => node.element)).toEqual(melds)
    expect(wrapper.get('.ym-player-panel').element).toBe(panel)
  })

  it('uses compact fixed four-player rows without shrinking public tracks or adding scrollers', () => {
    const style = document.createElement('style'); style.textContent = ['yaoming.css', 'playability.css', 'player-lanes.css', 'stable-player-lanes.css', 'stable-controls.css']
      .map(file => readFileSync(resolve(process.cwd(), 'src/yaoming', file), 'utf8')).join('\n'); document.head.append(style); elements.push(style)
    const wrapper = table(), tableStyle = getComputedStyle(wrapper.get('.ym-lanes-table').element)
    expect(tableStyle.gridTemplateRows).toBe('repeat(4, 146px)'); expect(tableStyle.height).toBe('614px')
    expect(tableStyle.padding).toBe('6px'); expect(tableStyle.gap).toBe('6px')
    const lanes = wrapper.findAll('.ym-player-lane')
    for (const lane of lanes) {
      const style = getComputedStyle(lane.element), padding = lane.classes().includes('ym-lane-self') ? '3px' : '4px'
      expect(style.paddingTop).toBe(padding); expect(style.paddingBottom).toBe(padding)
      expect(getComputedStyle(lane.get('.ym-lane-public').element).height).toBe('80px')
      expect(getComputedStyle(lane.get('.ym-lane-score').element).gridRow).toBe('5')
      expect(getComputedStyle(lane.get('.ym-lane-turn').element).height).toBe('14px')
    }
    expect(getComputedStyle(wrapper.get('.ym-player-panel').element).height).toBe('379px')
    expect(getComputedStyle(wrapper.get('.ym-action-bar').element).height).toBe('144px')
    for (const selector of ['.ym-lanes-table', '.ym-meld-strip', '.ym-lane-river-tiles']) expect(getComputedStyle(wrapper.get(selector).element).overflow).not.toMatch(/auto|scroll/)
    const css = readFileSync(resolve(process.cwd(), 'src/yaoming/stable-player-lanes.css'), 'utf8')
    expect(css).toContain('height: 614px'); expect(css).toContain('height: 506px'); expect(css).toContain('height: 498px')
  })

  it('restores the original three-player sizing when switching away from four players', async () => {
    const style = document.createElement('style'); style.textContent = ['yaoming.css', 'playability.css', 'player-lanes.css', 'stable-player-lanes.css', 'stable-controls.css']
      .map(file => readFileSync(resolve(process.cwd(), 'src/yaoming', file), 'utf8')).join('\n'); document.head.append(style); elements.push(style)
    const wrapper = table()
    await wrapper.setProps({ room: room() })
    const tableStyle = getComputedStyle(wrapper.get('.ym-lanes-table').element)
    expect(tableStyle.gridTemplateRows).toBe('repeat(3, 158px)'); expect(tableStyle.height).toBe('506px')
    expect(tableStyle.padding).toBe('8px'); expect(tableStyle.gap).toBe('8px')
    for (const lane of wrapper.findAll('.ym-player-lane')) {
      const style = getComputedStyle(lane.element), padding = lane.classes().includes('ym-lane-self') ? '9px' : '10px'
      expect(style.paddingTop).toBe(padding); expect(style.paddingBottom).toBe(padding)
    }
  })

  it('shows the four-player sidebar without disturbing the three-player fallback', async () => {
    const wrapper = keep(mount(RoomSidebar, { props: { room: four(), identity: null } }))
    expect(wrapper.text()).toContain('四人 · 136 张'); expect(wrapper.text()).toContain('东南八局')
    await wrapper.setProps({ room: room() })
    expect(wrapper.text()).toContain('三人 · 108 张'); expect(wrapper.text()).toContain('东南六局')
  })
})

describe('four-player meld source placement', () => {
  it.each([0, 1, 2, 3].flatMap(owner => [1, 2, 3].map(distance => [owner, distance])))('maps source for owner %s and distance %s', (owner, distance) => {
    const declared = tiles(0, 3), meld = { type: 'PONG' as const, tiles: declared, fromSeat: (owner + distance) % 4, claimedTileId: declared[0].id, concealed: false }
    const wrapper = keep(mount(MeldView, { props: { meld, ownerSeat: owner, capacity: 4 } }))
    const label = ['', '下家', '对家', '上家'][distance]
    expect(wrapper.text()).toContain(`碰 · ${label}`)
    const ids = wrapper.findAll('.ym-meld-slot').map(slot => slot.attributes('data-tile-id'))
    expect(ids.indexOf(declared[0].id)).toBe(distance === 1 ? 2 : distance === 2 ? 1 : 0)
    expect(wrapper.get('.ym-meld-horizontal').attributes('aria-label')).toBe(`${label}供牌`)
  })

  it('keeps an opposite-player added kong stacked and maintains live concealed-kong privacy', async () => {
    const declared = tiles(1, 4)
    const wrapper = keep(mount(MeldView, { props: { meld: { type: 'KONG', tiles: declared, fromSeat: 3, claimedTileId: declared[0].id, concealed: false, added: true }, ownerSeat: 1, capacity: 4 } }))
    expect(wrapper.text()).toContain('加杠 · 对家'); expect(wrapper.findAll('.ym-meld-added')).toHaveLength(1)
    expect(wrapper.findAll('.ym-meld-slot')[1].classes()).toContain('ym-meld-stack')
    await wrapper.setProps({ meld: { type: 'KONG', tiles: declared, fromSeat: 1, claimedTileId: '', concealed: true } })
    expect(wrapper.findAll('.ym-tile-back')).toHaveLength(2)
    await wrapper.setProps({ reveal: true })
    expect(wrapper.findAll('.ym-tile-back')).toHaveLength(0)
  })

  it('shows all four recorded result hands and scores without recomputing payments', () => {
    const value = four(), snapshot = result({ draw: true, fan: 0, rawFan: 0, winnerId: null,
      scores: value.players.map((p, i) => ({ playerId: p.id, name: p.name, score: p.score, delta: 0, rank: i + 1 })),
      hands: value.players.map(p => ({ playerId: p.id, hand: p.hand, melds: [] })), payments: [] })
    const wrapper = keep(mount(SettlementDialog, { props: { room: { ...value, status: 'HAND_END', result: snapshot }, busy: false, readonly: true } }))
    expect(wrapper.findAll('.ym-result-scores article')).toHaveLength(4)
    expect(wrapper.get('.ym-result-hands summary').text()).toBe('查看四家终局手牌')
    expect(wrapper.findAll('.ym-result-hands article')).toHaveLength(4)
    expect(wrapper.get('.ym-result-dialog').classes()).toContain('ym-four-player-result')
  })

  it('provides an isolated switchable four-player browser preview and safely returns from a North perspective to three', async () => {
    const wrapper = keep(mount(StableTablePreview))
    await wrapper.get('[aria-label="规则人数"]').setValue(4)
    await wrapper.get('[aria-label="本人座位"]').setValue(3)
    expect(wrapper.findAll('.ym-player-lane')).toHaveLength(4)
    expect(wrapper.get('.ym-lane-self').attributes('data-player-id')).toBe('p4')
    await wrapper.get('[aria-label="规则人数"]').setValue(3)
    expect(wrapper.findAll('.ym-player-lane')).toHaveLength(3)
    expect(wrapper.get('.ym-lane-self').attributes('data-player-id')).toBe('p1')
  })
})

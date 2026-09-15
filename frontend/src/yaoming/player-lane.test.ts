import { afterEach, describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount, type VueWrapper } from '@vue/test-utils'
import PlayerLane from './PlayerLane.vue'
import TileView from './TileView.vue'
import MeldView from './MeldView.vue'
import DiscardTile from './DiscardTile.vue'
import { player } from './testFixtures'
import type { Meld, Player, RoomView, Tile } from './types'

const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })
const tiles = (count: number, prefix = 'river'): Tile[] => Array.from({ length: count }, (_, index) => ({ id: `${prefix}-${index + 1}`, suit: 'BAMBOO', rank: index % 9 + 1, label: `${index % 9 + 1}条` }))
function lane(overrides: Partial<Player> = {}, view: { self?: boolean; current?: boolean; status?: string; lastDiscard?: RoomView['lastDiscard']; revealHand?: boolean } = {}) {
  const wrapper = mount(PlayerLane, { props: { player: { ...player(), ...overrides }, self: false, current: false, status: 'NEED_DISCARD', ...view } })
  wrappers.push(wrapper); return wrapper
}

describe('player lane identity and public hand privacy', () => {
  it.each([false, true])('renders backs only even when the view contains real hand tiles; self=%s', self => {
    const privateHand = tiles(14, 'private-hand')
    const wrapper = lane({ hand: privateHand, handSize: 14, drawnTileId: privateHand[13].id }, { self })
    const concealed = wrapper.get('.ym-concealed-hand')
    expect(concealed.findAll('.ym-tile-back')).toHaveLength(14)
    expect(concealed.findAll('.mahjong-art')).toHaveLength(0)
    expect(concealed.findAll('.ym-tile')).toHaveLength(14)
    expect(concealed.attributes('role')).toBe('img')
    expect(concealed.attributes('aria-label')).toContain('共 14 张')
    expect(concealed.findAll('[aria-hidden="true"]')).toHaveLength(14)
    for (const tile of privateHand) expect(wrapper.html()).not.toContain(tile.id)
    expect(wrapper.text()).not.toContain('手牌在下方')
    expect(wrapper.text()).not.toContain('场上玩家')
  })

  it.each([0, 1, 4, 7, 10, 13, 14])('uses the authoritative handSize %s rather than hand length', handSize => {
    const wrapper = lane({ hand: [], handSize }, { self: true })
    expect(wrapper.get('.ym-concealed-hand').findAll('.ym-tile-back')).toHaveLength(handSize)
    expect(wrapper.findAll('.ym-lane-hand-slot')).toHaveLength(14)
    expect(wrapper.findAll('.ym-lane-hand-slot-filled')).toHaveLength(handSize)
    expect(wrapper.findAll('.ym-lane-hand-slot:not(.ym-lane-hand-slot-filled) .ym-tile')).toHaveLength(0)
  })

  it('keeps the self border independent of the current player, preserving the provided wind and name', async () => {
    const wrapper = lane({ name: '长昵称要完整保留供查看', wind: '南', score: 23 }, { self: true, current: false })
    expect(wrapper.classes()).toContain('ym-lane-self')
    expect(wrapper.classes()).not.toContain('ym-lane-current')
    expect(wrapper.get('.ym-wind').text()).toBe('南')
    expect(wrapper.get('.ym-lane-name strong').attributes('title')).toBe('长昵称要完整保留供查看')
    expect(wrapper.get('.ym-lane-score').text()).toBe('23 点')
    expect(wrapper.get('.ym-lane-self-badge').text()).toBe('你')
    await wrapper.setProps({ current: true })
    expect(wrapper.classes()).toContain('ym-lane-self')
    expect(wrapper.classes()).toContain('ym-lane-current')
    expect(wrapper.get('.ym-lane-turn').text()).toBe('行动中')
    await wrapper.setProps({ self: false })
    expect(wrapper.classes()).not.toContain('ym-lane-self')
    expect(wrapper.classes()).toContain('ym-lane-current')
    expect(wrapper.find('.ym-lane-self-badge').exists()).toBe(false)
  })

  it.each([
    { status: 'WAITING', ready: false, acknowledged: false, expected: '等待准备' },
    { status: 'WAITING', ready: true, acknowledged: false, expected: '已准备' },
    { status: 'HAND_END', ready: false, acknowledged: false, expected: '等待结算确认' },
    { status: 'MATCH_END', ready: false, acknowledged: true, expected: '已确认结算' },
  ])('shows $expected with no active-turn styling outside play', ({ status, ready, acknowledged, expected }) => {
    const wrapper = lane({ ready, acknowledged }, { status, current: true })
    expect(wrapper.get('.ym-lane-status').text()).toContain(expected)
    expect(wrapper.classes()).not.toContain('ym-lane-current')
    expect(wrapper.get('.ym-lane-turn').text()).toBe('')
    expect(wrapper.get('.ym-lane-turn').attributes('aria-hidden')).toBe('true')
  })

  it('identifies a trustee as automatic tsumogiri and distinguishes a practice bot', () => {
    const trustee = lane({ trustee: true, trusteeReason: 'TIMEOUT' })
    expect(trustee.get('.ym-lane-status').text()).toContain('超时托管 · 自动摸切')
    expect(trustee.get('.ym-lane-status').text()).not.toContain('机器人')
    const bot = lane({ bot: true, trustee: true })
    expect(bot.get('.ym-lane-status').text()).toContain('机器人')
    expect(bot.get('.ym-lane-status').text()).not.toContain('自动摸切')
  })
})

describe('fixed public hand, meld and identity tracks', () => {
  it('keeps all fourteen slot, meld and score nodes when a draw changes 13 to 14 and a discard changes it back', async () => {
    const declared = tiles(3, 'pong')
    const meld: Meld = { type: 'PONG', tiles: declared, fromSeat: 2, claimedTileId: declared[0].id, concealed: false }
    const initial = { ...player(), handSize: 13, hand: tiles(13, 'private'), melds: [meld] }
    const wrapper = lane(initial)
    const slots = wrapper.findAll('.ym-lane-hand-slot').map(slot => slot.element)
    const meldStrip = wrapper.get('.ym-meld-strip').element, score = wrapper.get('.ym-lane-score').element
    const marker = wrapper.get('.ym-lane-turn').element
    for (const handSize of [14, 13, 10, 0]) {
      await wrapper.setProps({ player: { ...initial, handSize }, current: handSize === 14 })
      expect(wrapper.findAll('.ym-lane-hand-slot').map(slot => slot.element)).toEqual(slots)
      expect(wrapper.get('.ym-concealed-hand').findAll('.ym-tile-back')).toHaveLength(handSize)
      expect(wrapper.get('.ym-meld-strip').element).toBe(meldStrip)
      expect(wrapper.get('.ym-lane-score').element).toBe(score)
      expect(wrapper.get('.ym-lane-turn').element).toBe(marker)
      expect(wrapper.get('.ym-lane-turn').text()).toBe(handSize === 14 ? '行动中' : '')
    }
  })

  it('declares fixed hand/meld/score tracks and a one-row river independent of the amount of public content', () => {
    const styles = document.createElement('style'), host = document.createElement('div'), table = document.createElement('section')
    styles.textContent = ['yaoming.css', 'playability.css', 'player-lanes.css', 'stable-player-lanes.css']
      .map(file => readFileSync(resolve(process.cwd(), 'src/yaoming', file), 'utf8')).join('\n')
    host.className = 'ym-app'; table.className = 'ym-lanes-table'; host.append(table)
    document.head.append(styles); document.body.append(host)
    const wrapper = mount(PlayerLane, { props: { player: { ...player(), handSize: 14, discards: tiles(40) }, self: true, current: false, status: 'NEED_DISCARD' }, attachTo: table })
    try {
      expect(getComputedStyle(wrapper.get('.ym-lane-identity').element).gridTemplateRows).toBe('27px 18px 28px 14px 29px')
      expect(getComputedStyle(wrapper.get('.ym-lane-score').element).gridRow).toBe('5')
      expect(getComputedStyle(wrapper.get('.ym-lane-turn').element).gridRow).toBe('4')
      expect(getComputedStyle(wrapper.get('.ym-concealed-hand').element).gridTemplateColumns).toBe('repeat(14, minmax(0, 1fr))')
      expect(getComputedStyle(wrapper.get('.ym-meld-strip').element).gridRow).toBe('2')
      expect(getComputedStyle(wrapper.get('.ym-lane-river-tiles').element).gridTemplateRows).toBe('30px')
      expect(getComputedStyle(wrapper.get('.ym-lane-river-tiles').element).gridAutoFlow).toBe('column')
      for (const row of wrapper.findAll('.ym-lane-river-tile')) expect(getComputedStyle(row.element).gridRow).toBe('1')
      expect(getComputedStyle(wrapper.get('.ym-concealed-hand .ym-tile').element).aspectRatio).toBe('19 / 26')
      const finalSource = readFileSync(resolve(process.cwd(), 'src/yaoming/stable-player-lanes.css'), 'utf8')
      expect(finalSource).not.toMatch(/overflow(?:-[xy])?:\s*(?:auto|scroll)/)
      expect(finalSource).toContain('repeat(24, minmax(0, 1fr))')
      expect(finalSource).toContain('repeat(16, minmax(0, 1fr))')
    } finally { wrapper.unmount(); styles.remove(); host.remove() }
  })
})

describe('explicit readonly replay reveal', () => {
  it.each([false, true])('shows the supplied real hand in fixed slots and reveals concealed melds for replay self=%s', self => {
    const hand = tiles(11, 'replay-hand'), declared = tiles(4, 'replay-kong')
    const meld: Meld = { type: 'KONG', tiles: declared, fromSeat: 1, claimedTileId: '', concealed: true }
    const target = { ...player('p2', 1), hand, handSize: 11, melds: [meld], online: false, trustee: true, trusteeReason: 'TIMEOUT' as const }, snapshot = JSON.stringify(target)
    const wrapper = lane(target, { self, revealHand: true, status: 'HAND_END' })
    expect(wrapper.findAll('.ym-lane-hand-slot')).toHaveLength(14)
    expect(wrapper.findAll('.ym-lane-hand-slot[data-tile-id]').map(slot => slot.attributes('data-tile-id'))).toEqual(hand.map(tile => tile.id))
    expect(wrapper.get('.ym-concealed-hand').findAll('.mahjong-art')).toHaveLength(11)
    expect(wrapper.get('.ym-concealed-hand').findAll('.ym-tile-back')).toHaveLength(0)
    expect(wrapper.get('.ym-concealed-hand').attributes('aria-label')).toContain('复盘手牌，共 11 张')
    expect(wrapper.get('.ym-concealed-hand').attributes('role')).toBe('group')
    expect(wrapper.get('.ym-lane-status').text()).toBe('历史牌面')
    expect(wrapper.get('.ym-lane-status').attributes('title')).toBe('历史牌面')
    expect(wrapper.get('.ym-lane-identity').text()).not.toMatch(/暂时离线|自动摸切|等待结算确认/)
    if (self) expect(wrapper.get('.ym-lane-self-badge').text()).toBe('当前视角')
    else expect(wrapper.find('.ym-lane-self-badge').exists()).toBe(false)
    expect(wrapper.getComponent(MeldView).props('reveal')).toBe(true)
    expect(wrapper.getComponent(MeldView).findAll('.mahjong-art')).toHaveLength(4)
    expect(wrapper.emitted('action')).toBeUndefined()
    expect(JSON.stringify(target)).toBe(snapshot)
  })

  it('never fabricates absent replay hand tiles and hides them again when returning to a live opponent lane', async () => {
    const hand = tiles(13, 'historic')
    const wrapper = lane({ hand, handSize: 13 }, { revealHand: true })
    expect(wrapper.findAll('.ym-lane-hand-slot[data-tile-id]')).toHaveLength(13)
    await wrapper.setProps({ revealHand: false })
    expect(wrapper.findAll('.ym-lane-hand-slot[data-tile-id]')).toHaveLength(0)
    expect(wrapper.get('.ym-concealed-hand').findAll('.ym-tile-back')).toHaveLength(13)
    expect(wrapper.get('.ym-concealed-hand').findAll('.mahjong-art')).toHaveLength(0)
    await wrapper.setProps({ revealHand: true, player: { ...player(), hand: [], handSize: 13 } })
    expect(wrapper.findAll('.ym-lane-hand-slot')).toHaveLength(14)
    expect(wrapper.get('.ym-concealed-hand').findAllComponents(TileView)).toHaveLength(0)
  })

  it('switches the perspective badge back to the live self label without replacing its slot', async () => {
    const wrapper = lane({}, { self: true, revealHand: true })
    const badge = wrapper.get('.ym-lane-self-badge').element
    expect(wrapper.get('.ym-lane-self-badge').text()).toBe('当前视角')
    await wrapper.setProps({ revealHand: false })
    expect(wrapper.get('.ym-lane-self-badge').element).toBe(badge)
    expect(wrapper.get('.ym-lane-self-badge').text()).toBe('你')
  })
})

describe('player lane meld privacy and supply metadata', () => {
  it.each([false, true])('preserves concealed-kong visibility for self=%s', self => {
    const declared = tiles(4, 'kong')
    const meld: Meld = { type: 'KONG', tiles: declared, fromSeat: 1, claimedTileId: '', concealed: true }
    const wrapper = lane({ seat: 1, melds: [meld] }, { self })
    const rendered = wrapper.getComponent(MeldView)
    expect(rendered.props('reveal')).toBe(self)
    expect(rendered.props('ownerSeat')).toBe(1)
    expect(rendered.findAll('.ym-tile-back')).toHaveLength(self ? 0 : 2)
    expect(rendered.findAll('.mahjong-art')).toHaveLength(self ? 4 : 2)
    expect(wrapper.get('.ym-meld-strip').attributes('aria-label')).toBe(self ? '自己的副露区' : '玩家1 的副露区')
  })

  it('keeps called tiles horizontal and an added kong stacked, without moving the meld into the river', () => {
    const declared = tiles(4, 'added')
    const meld: Meld = { type: 'KONG', tiles: declared, fromSeat: 0, claimedTileId: declared[0].id, concealed: false, added: true }
    const wrapper = lane({ seat: 1, melds: [meld], discards: tiles(3) })
    expect(wrapper.getComponent(MeldView).props('meld')).toEqual(meld)
    expect(wrapper.findAll('.ym-meld-added')).toHaveLength(1)
    expect(wrapper.get('.ym-meld-strip').text()).toContain('加杠 · 上家')
    expect(wrapper.get('.ym-lane-river').findAllComponents(MeldView)).toHaveLength(0)
    expect(wrapper.findAllComponents(DiscardTile)).toHaveLength(3)
  })
})

describe('bounded river preview and full-river access', () => {
  it.each([0, 1, 16, 17, 24, 25, 40])('renders one chronological tail preview for a river of %s tiles', count => {
    const discards = tiles(count)
    const wrapper = lane({ discards })
    const list = wrapper.get('ol.ym-lane-river-tiles.ym-river')
    const start = Math.max(0, count - 24), mobileStart = Math.max(0, count - 16)
    const rows = list.findAll('li')
    expect(rows).toHaveLength(Math.min(count, 24))
    expect(wrapper.findAllComponents(DiscardTile).map(face => face.props('tile').id)).toEqual(discards.slice(start).map(tile => tile.id))
    expect(rows.map(row => Number(row.attributes('data-discard-index')))).toEqual(discards.slice(start).map((_, index) => start + index + 1))
    expect(list.attributes('start')).toBe(String(start + 1))
    expect(rows.filter(row => row.classes().includes('ym-lane-river-mobile-hidden'))).toHaveLength(mobileStart - start)
    expect(rows.filter(row => !row.classes().includes('ym-lane-river-mobile-hidden'))).toHaveLength(Math.min(count, 16))
    expect(wrapper.find('.ym-lane-river-more').exists()).toBe(count > 16)
    if (count > 16) expect(list.attributes('aria-label')).toContain('较早弃牌可通过查看全部牌河查看')
    expect(wrapper.findAll('[tabindex="0"]')).toHaveLength(0)
  })

  it('emits only the player ID to open the complete river and does not change public state', async () => {
    const target = { ...player('p2', 1), discards: tiles(28) }
    const snapshot = JSON.stringify(target)
    const wrapper = lane(target)
    expect(wrapper.get('.ym-lane-river-more').attributes('aria-label')).toBe('查看玩家2的全部牌河，共 28 张')
    await wrapper.get('.ym-lane-river-more').trigger('click')
    expect(wrapper.emitted('inspect')).toEqual([['p2']])
    expect(wrapper.emitted('action')).toBeUndefined()
    expect(JSON.stringify(target)).toBe(snapshot)
  })

  it('retains server tsumogiri/tedashi/unknown kinds and highlights only an unclaimed latest tile from this player', async () => {
    const discards = tiles(30), last = discards[29]
    const wrapper = lane({ discards, discardKinds: { [discards[27].id]: 'TSUMOGIRI', [discards[28].id]: 'TEDASHI' } },
      { lastDiscard: { tile: last, fromSeat: 0, claimed: false } })
    const faces = wrapper.findAllComponents(DiscardTile)
    expect(faces[21].props('kind')).toBe('TSUMOGIRI')
    expect(faces[21].classes()).toContain('ym-discard-tsumogiri')
    expect(faces[22].props('kind')).toBe('TEDASHI')
    expect(faces[22].classes()).not.toContain('ym-discard-tsumogiri')
    expect(faces[23].props('kind')).toBeUndefined()
    expect(wrapper.findAll('.ym-latest-river-tile')).toHaveLength(1)
    expect(faces[23].classes()).toContain('ym-latest-river-tile')
    await wrapper.setProps({ lastDiscard: { tile: last, fromSeat: 0, claimed: true } })
    expect(wrapper.findAll('.ym-latest-river-tile')).toHaveLength(0)
    await wrapper.setProps({ lastDiscard: { tile: last, fromSeat: 2, claimed: false } })
    expect(wrapper.findAll('.ym-latest-river-tile')).toHaveLength(0)
  })

  it('keeps existing tile nodes when another discard extends the tail, without mutating the input', async () => {
    const discards = tiles(25), currentPlayer = { ...player(), discards }
    const snapshot = JSON.stringify(currentPlayer), wrapper = lane(currentPlayer)
    const kept = wrapper.findAll('li').find(row => row.attributes('data-discard-index') === '25')!.element
    await wrapper.setProps({ player: { ...currentPlayer, discards: [...discards, { id: 'river-26', suit: 'DOTS', rank: 8, label: '八筒' }] } })
    expect(wrapper.findAll('li').find(row => row.attributes('data-discard-index') === '25')!.element).toBe(kept)
    expect(wrapper.findAll('li')).toHaveLength(24)
    expect(JSON.stringify(currentPlayer)).toBe(snapshot)
  })
})

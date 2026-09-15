import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import DiscardTile from './DiscardTile.vue'
import TableView from './TableView.vue'
import RoomSidebar from './RoomSidebar.vue'
import ReplayPage from './ReplayPage.vue'
import HandTile from './HandTile.vue'
import MeldView from './MeldView.vue'
import WaitTiles from './WaitTiles.vue'
import { replayJson } from './replayExport'
import { yaomingApi } from './store'
import { player, room } from './testFixtures'
import type { DiscardKind, HandRecord, Meld, Player, Tile, WaitHint } from './types'

const five: Tile = { id: 'b5-drawn', suit: 'BAMBOO', rank: 5, label: '五条' }
const copy: Tile = { ...five, id: 'b5-in-hand' }
const identity = { roomId: 'room1', playerId: 'p1', token: 'private-discard-kind-test' }
const wrappers: VueWrapper[] = []
const keep = <T extends VueWrapper>(wrapper: T): T => { wrappers.push(wrapper); return wrapper }
const pong: Meld = { type: 'PONG', tiles: [0, 1, 2].map(i => ({ ...five, id: `pong-${i}` })), claimedTileId: 'pong-0', fromSeat: 1, concealed: false }
function threePlayers(): Player[] {
  return [player(), player('p2', 1), player('p3', 2)].map(p => ({
    ...p, discards: [{ ...five, id: `${p.id}-drawn` }, { ...copy, id: `${p.id}-held` }],
    discardKinds: { [`${p.id}-drawn`]: 'TSUMOGIRI', [`${p.id}-held`]: 'TEDASHI' },
  }))
}
function replayRecord(): HandRecord {
  const players = threePlayers()
  players[0].hand = [copy]; players[0].melds = [pong]
  return { roomId: 'room1', roomName: '弃牌测试', round: 1, roundLabel: '东一局', startedAt: 1, completedAt: 3, complete: true, incomplete: false, result: null,
    frames: [
      { index: 0, timestamp: 1, type: 'DISCARD', actorSeat: 0, message: '摸切五条', status: 'REACTION', currentSeat: 0, wallCount: 50, dealerSeat: 0,
        players, lastDiscard: { tile: players[0].discards[0], fromSeat: 0, claimed: false, kind: 'TSUMOGIRI' }, dice: null, result: null },
      { index: 1, timestamp: 2, type: 'PONG', actorSeat: 1, message: '被碰走的摸切', status: 'NEED_DISCARD', currentSeat: 1, wallCount: 50, dealerSeat: 0,
        players: players.map((p, i) => ({ ...p, discards: i === 0 ? [p.discards[1]] : p.discards })),
        lastDiscard: { tile: players[0].discards[0], fromSeat: 0, claimed: true, kind: 'TSUMOGIRI' }, dice: null, result: null },
      { index: 2, timestamp: 3, type: 'DISCARD', actorSeat: 2, message: '历史未知类型', status: 'NEED_DRAW', currentSeat: 0, wallCount: 50, dealerSeat: 0,
        players: players.map((p, i) => ({ ...p, discardKinds: i === 1 ? null : undefined })),
        lastDiscard: { tile: players[2].discards[0], fromSeat: 2, claimed: false }, dice: null, result: null },
    ],
  }
}

beforeEach(() => { vi.restoreAllMocks(); localStorage.clear() })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('shared physical discard annotation', () => {
  it.each([
    ['TSUMOGIRI', '五条 · 摸切', true],
    ['TEDASHI', '五条 · 手切', false],
    [null, '五条', false],
    [undefined, '五条', false],
    ['FUTURE_KIND', '五条', false],
  ] as const)('renders %s without guessing missing or future metadata', (kind, name, marked) => {
    const wrapper = keep(mount(DiscardTile, { props: { tile: five, kind: kind as DiscardKind | null | undefined, small: true } }))
    expect(wrapper.element.tagName).toBe('SPAN')
    expect(wrapper.classes()).toContain('ym-tile')
    expect(wrapper.classes('ym-discard-tsumogiri')).toBe(marked)
    expect(wrapper.attributes('title')).toBe(name)
    expect(wrapper.attributes('aria-label')).toBe(name)
    expect(wrapper.attributes('role')).toBe('img')
    expect(wrapper.attributes('data-discard-kind')).toBe(kind === 'TSUMOGIRI' || kind === 'TEDASHI' ? kind : undefined)
    expect(wrapper.findAll('svg.mahjong-art')).toHaveLength(1)
    expect(wrapper.find('svg.mahjong-art').attributes('data-artwork')).toBe('mahjong-graphic')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('keeps red-tile meaning, small/selected and caller classes without changing upstream art', () => {
    const wrapper = keep(mount(DiscardTile, { props: { tile: { ...five, red: true }, kind: 'TSUMOGIRI', small: true, selected: true }, attrs: { class: 'ym-latest-river-tile' } }))
    expect(wrapper.attributes('aria-label')).toBe('红宝牌 五条 · 摸切')
    expect(wrapper.classes()).toEqual(expect.arrayContaining(['ym-tile-small', 'ym-tile-selected', 'ym-latest-river-tile']))
    expect(wrapper.get('svg.mahjong-art').attributes('data-source-tile')).toBe('0s')
    expect(wrapper.findAll('.ym-discard-tile .ym-tile')).toHaveLength(0)
  })

  it('reuses the same root and all dimensions while changing between known and unknown kinds', async () => {
    const wrapper = keep(mount(DiscardTile, { props: { tile: five, small: true } }))
    const element = wrapper.element
    for (const kind of ['TSUMOGIRI', 'TEDASHI', null] as const) {
      await wrapper.setProps({ kind })
      expect(wrapper.element).toBe(element)
      expect(wrapper.attributes('style')).toBeUndefined()
      expect(wrapper.classes()).toContain('ym-tile-small')
      expect(wrapper.findAll('svg')).toHaveLength(1)
    }
  })
})

describe('live table discard provenance', () => {
  it('uses each entity ID for all three rivers and the explicit latest-discard kind', () => {
    const players = threePlayers()
    const value = room({ players, lastDiscard: { tile: players[2].discards[0], fromSeat: 2, claimed: false, kind: 'TSUMOGIRI' } })
    const wrapper = keep(mount(TableView, { props: { room: value, busy: false } }))
    const sidebar = keep(mount(RoomSidebar, { props: { room: value, identity } }))
    const rivers = wrapper.findAll('.ym-river')
    expect(rivers).toHaveLength(3)
    for (const river of rivers) {
      expect(river.findAll('.ym-discard-tile')).toHaveLength(2)
      expect(river.findAll('.ym-discard-tsumogiri')).toHaveLength(1)
      expect(river.findAll('[aria-label="五条 · 手切"]')).toHaveLength(1)
    }
    expect(sidebar.get('.ym-last-discard .ym-discard-tile').attributes('aria-label')).toBe('五条 · 摸切')
    expect(wrapper.find('.ym-last-discard').exists()).toBe(false)
    expect(wrapper.findAll('.ym-latest-river-tile')).toHaveLength(1)
  })

  it('retains the taken latest discard type but never recreates it in a river', () => {
    const me = { ...player(), discards: [copy], discardKinds: { [five.id]: 'TSUMOGIRI' as const, [copy.id]: 'TEDASHI' as const } }
    const value = room({ players: [me], lastDiscard: { tile: five, fromSeat: 0, claimed: true, kind: 'TSUMOGIRI' } })
    const wrapper = keep(mount(TableView, { props: { room: value, busy: false } }))
    const sidebar = keep(mount(RoomSidebar, { props: { room: value, identity } }))
    expect(sidebar.get('.ym-last-discard').text()).toContain('已被取走')
    expect(sidebar.get('.ym-last-discard .ym-discard-tile').classes()).toContain('ym-discard-tsumogiri')
    expect(wrapper.findAll('.ym-river .ym-discard-tile')).toHaveLength(1)
    expect(wrapper.findAll('.ym-river .ym-discard-tsumogiri, .ym-latest-river-tile')).toHaveLength(0)
  })

  it('does not infer unknown kinds from a matching hand draw ID or another equal tile', () => {
    const me = { ...player(), hand: [five], drawnTileId: five.id, discards: [copy], discardKinds: { [five.id]: 'TSUMOGIRI' as const } }
    const wrapper = keep(mount(TableView, { props: { room: room({ players: [me], lastDiscard: { tile: copy, fromSeat: 0, claimed: false } }), busy: false } }))
    expect(wrapper.findAll('.ym-discard-tsumogiri')).toHaveLength(0)
    for (const discarded of wrapper.findAll('.ym-discard-tile')) expect(discarded.attributes('aria-label')).toBe('五条')
    expect(wrapper.get('.ym-draw-slot .ym-tile').classes()).not.toContain('ym-discard-tile')
  })

  it('keeps all non-river cards free of discard markers, even for equal tile entities', () => {
    const waits: WaitHint[] = [{ tile: five, unseenCount: 1, canTsumo: true, tsumoFan: 4, canRon: true, ronFan: 4, ronReason: '' }]
    const surface = defineComponent({ setup: () => () => h('div', [h(HandTile, { tile: five, selected: false, disabled: true }), h(MeldView, { meld: pong, ownerSeat: 0 }), h(WaitTiles, { waits })]) })
    const wrapper = keep(mount(surface))
    expect(wrapper.findAll('.ym-tile').length).toBeGreaterThan(3)
    expect(wrapper.findAll('.ym-discard-tile, .ym-discard-tsumogiri, [data-discard-kind]')).toHaveLength(0)
  })

  it('preserves the fixed track nodes and dimensions contract for all-river metadata updates', async () => {
    const initial = room({ players: threePlayers() })
    const wrapper = keep(mount(TableView, { props: { room: initial, busy: false } }))
    const selectors = ['.ym-table', '.ym-player-panel']
    const elements = selectors.map(selector => wrapper.get(selector).element)
    const lanes = wrapper.findAll('.ym-player-lane').map(lane => lane.element)
    const roots = wrapper.findAll('.ym-river .ym-tile').map(card => card.element)
    await wrapper.setProps({ room: { ...initial, version: 2, players: initial.players.map(p => ({ ...p, discardKinds: null })) } })
    expect(selectors.map(selector => wrapper.get(selector).element)).toEqual(elements)
    expect(wrapper.findAll('.ym-player-lane').map(lane => lane.element)).toEqual(lanes)
    expect(lanes).toHaveLength(3)
    expect(wrapper.findAll('.ym-river .ym-tile').map(card => card.element)).toEqual(roots)
    expect(wrapper.findAll('.ym-discard-tsumogiri')).toHaveLength(0)
    const component = readFileSync(resolve(process.cwd(), 'src/yaoming/DiscardTile.vue'), 'utf8')
    const style = component.match(/<style scoped>([\s\S]*?)<\/style>/)![1]
    expect(style).toMatch(/\.ym-discard-tsumogiri::after\s*\{[^}]*position:\s*absolute;/)
    expect(style).toContain('bottom: -4px')
    expect(style).not.toMatch(/(?:margin|padding|display|transform|line-height):/)
    const tableStyle = readFileSync(resolve(process.cwd(), 'src/yaoming/player-lanes.css'), 'utf8')
    expect(tableStyle).toMatch(/\.ym-lanes-table \.ym-river\.ym-lane-river-tiles\s*\{[^}]*grid-auto-rows:\s*[\d.]+px;[^}]*gap:\s*[\d.]+px\s+[\d.]+px;/)
    expect(tableStyle).toMatch(/\.ym-lanes-table \.ym-river\.ym-lane-river-tiles\s*\{[^}]*padding:\s*[\d.]+px\s+[\d.]+px\s*[\d.]+px;[^}]*overflow:\s*visible;/)
  })
})

describe('readonly replay and safe discard exports', () => {
  function mockReplay(data: HandRecord) {
    vi.spyOn(yaomingApi, 'get').mockImplementation(async path => ({ data: path === '/replays'
      ? { roomId: 'room1', roomName: '弃牌测试', hands: [{ round: 1, roundLabel: '东一局', startedAt: 1, completedAt: 3, frameCount: 3, incomplete: false, title: '已结束' }], note: '' }
      : data }))
  }

  it('annotates all completed-frame rivers and latest discard, then preserves claimed and unknown states', async () => {
    const record = replayRecord(), before = JSON.stringify(record)
    mockReplay(record)
    const post = vi.spyOn(yaomingApi, 'post')
    const wrapper = keep(mount(ReplayPage, { props: { identity, room: null, history: [] } }))
    await flushPromises()
    expect(wrapper.findAll('.ym-player-lane .ym-river .ym-discard-tsumogiri')).toHaveLength(3)
    expect(wrapper.get('.ym-replay-latest .ym-discard-tile').attributes('aria-label')).toBe('五条 · 摸切')
    expect(wrapper.findAll('.ym-concealed-hand .ym-discard-tile, .ym-meld-strip .ym-discard-tile')).toHaveLength(0)
    await wrapper.get('input[aria-label="复盘进度"]').setValue('1')
    expect(wrapper.findAll('.ym-player-lane .ym-river .ym-discard-tsumogiri')).toHaveLength(2)
    expect(wrapper.get('.ym-replay-latest').text()).toContain('已被取走')
    expect(wrapper.get('.ym-replay-latest .ym-discard-tile').attributes('data-discard-kind')).toBe('TSUMOGIRI')
    await wrapper.get('input[aria-label="复盘进度"]').setValue('2')
    expect(wrapper.findAll('.ym-discard-tsumogiri')).toHaveLength(0)
    for (const discarded of wrapper.findAll('.ym-discard-tile')) expect(discarded.attributes('aria-label')).toBe('五条')
    expect(post).not.toHaveBeenCalled()
    expect(wrapper.emitted('action')).toBeUndefined()
    expect(JSON.stringify(record)).toBe(before)
  })

  it('exports validated public river kinds and the taken latest kind without spreading arbitrary map entries', () => {
    const record = replayRecord()
    Object.assign(record.frames[0].players[0].discardKinds!, { 'hidden-private-id': 'TSUMOGIRI', token: 'private-credential' })
    const exported = replayJson(record), data = JSON.parse(exported)
    expect(data.frames[0].players[0].discardKinds).toEqual({ 'p1-drawn': 'TSUMOGIRI', 'p1-held': 'TEDASHI' })
    expect(data.frames[1].lastDiscard).toMatchObject({ claimed: true, kind: 'TSUMOGIRI' })
    expect(data.frames[1].players[0].discardKinds).toEqual({ 'p1-held': 'TEDASHI' })
    expect(exported).not.toMatch(/hidden-private-id|private-credential|token/)
  })

  it('exports unknown history as null, and never treats unsupported values as hand discards', () => {
    const record = replayRecord()
    Object.assign(record.frames[0].players[0].discardKinds!, { 'p1-drawn': { token: 'do-not-export' }, 'p1-held': 'FUTURE_KIND' })
    Object.assign(record.frames[0].lastDiscard!, { kind: { token: 'do-not-export' } })
    const exported = replayJson(record), data = JSON.parse(exported)
    expect(data.frames[0].players[0].discardKinds).toEqual({})
    expect(data.frames[0].lastDiscard.kind).toBeNull()
    expect(data.frames[2].players.map((p: { discardKinds: unknown }) => p.discardKinds)).toEqual([null, null, null])
    expect(data.frames[2].lastDiscard.kind).toBeNull()
    expect(exported).not.toMatch(/do-not-export|FUTURE_KIND/)
  })
})

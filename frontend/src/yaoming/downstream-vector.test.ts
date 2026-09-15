import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import TableView from './TableView.vue'
import SettlementDialog from './SettlementDialog.vue'
import ReplayPage from './ReplayPage.vue'
import RulesPage from './RulesPage.vue'
import HintsPanel from './HintsPanel.vue'
import { TILE_ARTWORK_IMAGE, TILE_ARTWORK_SIZE, tileArtwork } from '../tileArtwork'
import { yaomingApi } from './store'
import { player, result, room } from './testFixtures'
import type { HandRecord, HintResponse, Meld, Tile } from './types'

const sevenBamboo: Tile = { id: 't1', suit: 'BAMBOO', rank: 7, label: '七条' }
const sevenDots: Tile = { id: 'dots-seven', suit: 'DOTS', rank: 7, label: '七筒' }
const identity = { roomId: 'room1', playerId: 'p1', token: 'private-vector-test' }
const concealedKong: Meld = { type: 'KONG', tiles: [0, 1, 2, 3].map(index => ({ ...sevenDots, id: `kong-${index}` })), fromSeat: 0, claimedTileId: '', concealed: true }

/** Catch downstream stubs/alternate renderers and clashing shared SVG definitions. */
function expectSharedUpstreamFaces(wrapper: VueWrapper) {
  const faces = wrapper.findAll('svg.mahjong-art')
  expect(faces.length).toBeGreaterThan(0)
  expect(faces.every(face => face.attributes('data-artwork') === 'mahjong-graphic')).toBe(true)
  expect(wrapper.findAll('img,foreignObject,.aka-mark,svg.mahjong-art text')).toHaveLength(0)
  const clips = wrapper.findAll('clipPath').map(clip => clip.attributes('id'))
  expect(new Set(clips).size).toBe(clips.length)
  expect(wrapper.findAll('image.mahjong-upstream-image')).toHaveLength(faces.length)
  for (const face of faces) {
    const faceKey = face.attributes('data-face')
    if (!faceKey) throw new Error('A shared tile face must expose its suit and rank')
    const [suit, rank] = faceKey.split('-')
    const artwork = tileArtwork(suit, Number(rank), face.attributes('data-source-tile')?.startsWith('0'))!
    expect(artwork).toBeDefined()
    expect(face.attributes('viewBox')).toBe('0 0 60 84')
    expect(face.attributes('data-source-tile')).toBe(artwork.file)
    expect(face.attributes('data-reference-crop')).toBeUndefined()
    const image = face.get('image.mahjong-upstream-image'), clipId = face.get('clipPath').attributes('id')
    expect(image.attributes('href')).toBe(TILE_ARTWORK_IMAGE)
    expect(image.attributes('width')).toBe(String(TILE_ARTWORK_SIZE.width)); expect(image.attributes('height')).toBe(String(TILE_ARTWORK_SIZE.height))
    expect(image.element.parentElement?.getAttribute('clip-path')).toBe(`url(#${clipId})`)
    const scale = Math.min(60 / artwork.width, 84 / artwork.height)
    expect(image.attributes('transform')).toBe(`translate(0 ${(84 - artwork.height * scale) / 2}) scale(${scale}) translate(${-artwork.x} ${-artwork.y})`)
    expect(face.findAll('image')).toHaveLength(1)
    expect(face.findAll('filter,[filter]')).toHaveLength(0)
    expect(image.attributes('href')).not.toMatch(/^(?:https?:|data:)/)
  }
}

beforeEach(() => { vi.restoreAllMocks(); localStorage.clear(); sessionStorage.clear() })
afterEach(() => { vi.useRealTimers() })

describe('upstream SVG faces through live and completed-game presentation', () => {
  it('uses fixed upstream faces for private hand, river and exposed melds while a supplied opponent hand stays hidden', () => {
    const me = { ...player(), hand: [sevenBamboo], discards: [sevenDots], melds: [concealedKong] }
    const other = { ...player('p2', 1), hand: [{ ...sevenDots, id: 'secret-opponent-id', label: '不得展示的对手牌' }], melds: [{ ...concealedKong, fromSeat: 1 }] }
    const wrapper = mount(TableView, { props: { room: room({ players: [me, other] }), busy: false } })
    expectSharedUpstreamFaces(wrapper)
    expect(wrapper.find('.ym-own-hand [data-face="BAMBOO-7"]').exists()).toBe(true)
    const ownLane = wrapper.get('.ym-lane-self')
    expect(ownLane.find('.ym-lane-river [data-face="DOTS-7"]').exists()).toBe(true)
    expect(ownLane.findAll('.ym-concealed-hand .ym-tile-back')).toHaveLength(13)
    expect(ownLane.findAll('.ym-concealed-hand svg')).toHaveLength(0)
    expect(ownLane.findAll('.ym-meld-strip .ym-tile-back')).toHaveLength(0)
    expect(ownLane.findAll('.ym-meld-strip svg.mahjong-art')).toHaveLength(4)
    expect(wrapper.find('.ym-player-panel .ym-lane-river').exists()).toBe(false)
    const opponent = wrapper.get('[aria-label="玩家2 的区域"]')
    expect(opponent.findAll('.ym-concealed-hand svg')).toHaveLength(0)
    expect(opponent.findAll('.ym-concealed-hand .ym-tile-back')).toHaveLength(13)
    expect(opponent.findAll('.ym-meld-strip .ym-tile-back')).toHaveLength(2)
    expect(opponent.html()).not.toMatch(/不得展示|secret-opponent-id/)
    wrapper.unmount()
  })
  it('preserves the settlement winning entity and fully revealed kong while every face uses the new renderer', () => {
    const settled = result({ winningTile: sevenBamboo, hands: [{ playerId: 'p1', hand: [sevenBamboo], melds: [concealedKong] }] })
    const before = JSON.stringify(settled)
    const wrapper = mount(SettlementDialog, { props: { room: room({ status: 'HAND_END', result: settled }), busy: false } })
    expectSharedUpstreamFaces(wrapper)
    expect(wrapper.findAll('[data-winning-tile="true"]')).toHaveLength(1)
    expect(wrapper.get('[data-winning-tile="true"]').attributes('data-tile-id')).toBe('t1')
    expect(wrapper.findAll('.ym-winning-melds svg.mahjong-art')).toHaveLength(4)
    expect(wrapper.findAll('.ym-winning-melds .ym-tile-back')).toHaveLength(0)
    expect(JSON.stringify(settled)).toBe(before)
    wrapper.unmount()
  })
  it('keeps replay steps readonly while updating shared faces, claimed source and final score presentation', async () => {
    const participants = [player(), player('p2', 1), player('p3', 2)].map((entry, index) => ({ ...entry, hand: [{ ...sevenBamboo, id: `hand-${index}` }] }))
    const hand: HandRecord = { roomId: 'room1', roomName: '视觉牌谱', round: 1, roundLabel: '东一局', startedAt: 1, completedAt: 2, complete: true, incomplete: false, result: result(), frames: [
      { index: 0, timestamp: 1, type: 'START', actorSeat: -1, message: '发牌', status: 'NEED_DRAW', currentSeat: 0, wallCount: 68, dealerSeat: 0, players: participants, lastDiscard: null, dice: null, result: null },
      { index: 1, timestamp: 2, type: 'WIN', actorSeat: 0, message: '和牌', status: 'HAND_END', currentSeat: 0, wallCount: 60, dealerSeat: 0, players: participants.map((entry, index) => ({ ...entry, melds: index ? [] : [concealedKong] })), lastDiscard: { tile: sevenDots, fromSeat: 2, claimed: true }, dice: null, result: result() },
    ] }
    vi.spyOn(yaomingApi, 'get').mockImplementation(async path => ({ data: path === '/replays' ? { roomId: 'room1', roomName: '视觉牌谱', hands: [{ round: 1, roundLabel: '东一局', startedAt: 1, completedAt: 2, frameCount: 2, incomplete: false, title: '和牌' }], note: '' } : hand }))
    const post = vi.spyOn(yaomingApi, 'post')
    const wrapper = mount(ReplayPage, { props: { identity, room: null, history: [] } })
    await flushPromises(); expectSharedUpstreamFaces(wrapper)
    expect(wrapper.findAll('.ym-player-lane .ym-concealed-hand svg.mahjong-art')).toHaveLength(3)
    await wrapper.get('input[aria-label="复盘进度"]').setValue('1')
    expectSharedUpstreamFaces(wrapper)
    expect(wrapper.get('.ym-replay-latest').text()).toContain('已被取走')
    expect(wrapper.findAll('.ym-player-lane .ym-meld-strip svg.mahjong-art')).toHaveLength(4)
    expect(wrapper.find('.ym-replay-result').exists()).toBe(true)
    expect(post).not.toHaveBeenCalled(); expect(wrapper.findComponent(TableView).props('readonly')).toBe(true)
    wrapper.findAll('.ym-hand-tile').forEach(button => expect(button.attributes('disabled')).toBeDefined())
    wrapper.unmount()
  })
  it('renders the fixed 27-kind rule catalog through the same upstream component without changing its tile values', () => {
    const tiles: Tile[] = ['CHARACTERS', 'BAMBOO', 'DOTS', 'HONORS'].flatMap(suit => {
      const ranks = suit === 'CHARACTERS' ? [1, 5, 9] : suit === 'HONORS' ? [1, 2, 3, 5, 6, 7] : [1, 2, 3, 4, 5, 6, 7, 8, 9]
      return ranks.map(rank => ({ id: `${suit}-${rank}`, suit, rank, label: `${suit}-${rank}` }))
    })
    const wrapper = mount(RulesPage, { props: { rules: { name: '要命麻将', version: 'test', description: '', notes: [], fans: [], tiles } } })
    expectSharedUpstreamFaces(wrapper)
    expect(wrapper.findAll('.ym-tile-catalog svg')).toHaveLength(27)
    expect(wrapper.findAll('[data-face="HONORS-4"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-face="BAMBOO-7"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-face="DOTS-7"]')).toHaveLength(1)
    expect(wrapper.get('[data-face="HONORS-5"]').attributes('data-source-tile')).toBe('7z')
    expect(wrapper.get('[data-face="HONORS-6"]').attributes('data-source-tile')).toBe('6z')
    expect(wrapper.get('[data-face="HONORS-7"]').attributes('data-source-tile')).toBe('5z')
    wrapper.unmount()
  })
  it('uses upstream wait candidates without mutating the hint response or turning a hint into a game action', async () => {
    vi.useFakeTimers()
    const data: HintResponse = { ...identity, version: 1, analysis: { mode: 'WAIT', discards: [], note: '', waits: [{ tile: sevenDots, unseenCount: 2, canTsumo: true, tsumoFan: 4, canRon: false, ronFan: 3, ronReason: '不足 4 番' }] } }
    const before = JSON.stringify(data)
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data })
    const post = vi.spyOn(yaomingApi, 'post')
    const wrapper = mount(HintsPanel, { props: { identity, room: room() } })
    await vi.advanceTimersByTimeAsync(200); await flushPromises()
    expectSharedUpstreamFaces(wrapper)
    expect(wrapper.find('.ym-hint-waits [data-face="DOTS-7"]').exists()).toBe(true)
    expect(wrapper.get('.ym-hint-count').text()).toBe('2张'); expect(wrapper.get('.ym-hint-fans').text()).toBe('自摸4番')
    expect(wrapper.text()).not.toMatch(/不足 4 番|点和3番/)
    expect(post).not.toHaveBeenCalled(); expect(JSON.stringify(data)).toBe(before)
    wrapper.unmount()
  })
})

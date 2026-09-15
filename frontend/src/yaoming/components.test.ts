import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TableView from './TableView.vue'
import SettlementDialog from './SettlementDialog.vue'
import MeldView from './MeldView.vue'
import { player, result, room, tile } from './testFixtures'

describe('yaoming table and shared settlement', () => {
  beforeEach(() => localStorage.clear())
  it.each([0, 1, 2])('keeps East/South/West public lanes fixed and marks self at seat %s', async (mySeat) => {
    const me = player('p1', mySeat)
    const next = player('next', (mySeat + 1) % 3)
    const previous = player('previous', (mySeat + 2) % 3)
    const wrapper = mount(TableView, { props: { room: room({ players: [next, me, previous] }), busy: false } })
    const lanes = wrapper.findAll('.ym-player-lane')
    expect(lanes.map(lane => lane.get('.ym-wind').text())).toEqual(['东', '南', '西'])
    const expected = [me, next, previous].sort((first, second) => first.seat - second.seat)
    expect(lanes.map(lane => lane.attributes('aria-label'))).toEqual(expected.map(player => `${player.name} 的区域`))
    expect(wrapper.get('.ym-lane-self').attributes('data-player-id')).toBe('p1')
    await wrapper.setProps({ room: room({ players: [previous, next, me], currentSeat: next.seat }) })
    expect(wrapper.findAll('.ym-player-lane').map(lane => lane.attributes('data-player-id'))).toEqual(expected.map(player => player.id))
    expect(wrapper.get('.ym-lane-self').attributes('data-player-id')).toBe('p1')
    expect(wrapper.get('.ym-lane-current').attributes('data-player-id')).toBe('next')
    wrapper.unmount()
  })
  it('prompts the player to discard when no other action buttons are available', () => {
    const wrapper = mount(TableView, { props: { room: room(), busy: false } })
    expect(wrapper.find('.ym-action-bar').text()).toContain('请先选择一张牌')
    expect(wrapper.find('.ym-action-bar').text()).not.toContain('再次点击或按出牌按钮')
    expect(wrapper.find('.ym-action-bar').text()).not.toContain('等待牌局推进')
    wrapper.unmount()
  })
  it('keeps the South lane occupied and the West lane empty while waiting for the third player', () => {
    const wrapper = mount(TableView, { props: { room: room({ status: 'WAITING', players: [player(), player('next', 1)] }), busy: false } })
    const lanes = wrapper.findAll('.ym-lanes-table > article')
    expect(lanes).toHaveLength(3)
    expect(lanes.map(lane => lane.get('.ym-wind').text())).toEqual(['东', '南', '西'])
    expect(lanes[1].attributes('data-player-id')).toBe('next')
    expect(lanes[2].classes()).toContain('ym-lane-empty')
    expect(lanes[2].attributes('aria-label')).toBe('西家空位')
    wrapper.unmount()
  })
  it('shows prepared waiting status without inviting an already-ready player to prepare again', () => {
    const me = player(); me.ready = true
    const wrapper = mount(TableView, { props: { room: room({ status: 'WAITING', players: [me], actions: [{ type: 'READY', label: '取消准备', tileIds: [] }] }), busy: false } })
    expect(wrapper.find('.ym-own-info').text()).toContain('已准备，等待其他玩家')
    expect(wrapper.find('.ym-action-bar').text()).toContain('取消准备')
    expect(wrapper.find('.ym-own-info').text()).not.toContain('轮到你了')
    wrapper.unmount()
  })
  it('renders opponent hands as backs and keeps rivers separate from player labels and melds', () => {
    const opponent = player('p2', 1); opponent.hand = [{ ...tile, id: 'secret-tile', label: '秘密牌' }]; opponent.discards = [tile]
    const wrapper = mount(TableView, { props: { room: room({ players: [player(), opponent] }), busy: false } })
    expect(wrapper.find('[aria-label="玩家2 的区域"]').text()).not.toContain('秘密牌')
    expect(wrapper.get('[aria-label="玩家2 的区域"]').findAll('.ym-concealed-hand .ym-tile-back')).toHaveLength(13)
    expect(wrapper.get('.ym-lane-self').findAll('.ym-concealed-hand .ym-tile-back')).toHaveLength(13)
    expect(wrapper.findAll('.ym-concealed-hand .ym-tile-back')).toHaveLength(26)
    expect(wrapper.find('.ym-seat-info .ym-river').exists()).toBe(false)
    expect(wrapper.find('.ym-meld-strip .ym-river').exists()).toBe(false)
    expect(wrapper.get('[aria-label="玩家2 的区域"]').find('.ym-lane-river svg').exists()).toBe(true)
    wrapper.unmount()
  })
  it('offers exact server chi variants and never adds unsupported WIN', async () => {
    const otherTile = { ...tile, id: 't2', rank: 2, label: '二条' }
    const me = player(); me.hand = [tile, otherTile]
    const chi = { type: 'CHI', label: '吃', tileIds: ['t1', 't2'] }
    const wrapper = mount(TableView, { props: { room: room({ players: [me], actions: [chi] }), busy: false } })
    expect(wrapper.find('.ym-action-bar').text()).not.toContain('自摸')
    expect(wrapper.findAll('.ym-action-tiles svg')).toHaveLength(2)
    await wrapper.find('.ym-action-bar button').trigger('click')
    expect(wrapper.emitted('action')?.[0][0]).toEqual(chi)
    expect(wrapper.find('.ym-hand-tile').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('uses the immutable score snapshot after another player leaves', () => {
    const wrapper = mount(SettlementDialog, { props: { room: room({ result: result(), players: [player()] }), busy: false } })
    expect(wrapper.find('.ym-result-scores').text()).toContain('乙')
    expect(wrapper.find('.ym-result-scores').text()).toContain('丙')
    expect(wrapper.find('.ym-payment').text()).toContain('乙 → 甲')
  })
  it('deduplicates equivalent chi copies while preserving distinct shapes and original legal IDs', async () => {
    const me = player()
    me.hand = [tile, { ...tile, id: 'one-b' }, { ...tile, id: 'two-a', rank: 2 }, { ...tile, id: 'two-b', rank: 2 }, { ...tile, id: 'three', rank: 3 }]
    const first = { type: 'CHI', label: '吃一二', tileIds: ['t1', 'two-a'] }
    const actions = [first, { type: 'CHI', label: '吃一二', tileIds: ['one-b', 'two-b'] }, { type: 'CHI', label: '吃二三', tileIds: ['two-a', 'three'] }]
    const wrapper = mount(TableView, { props: { room: room({ players: [me], actions }), busy: false } })
    const buttons = wrapper.findAll('.ym-action-bar button')
    expect(buttons).toHaveLength(2)
    await buttons[0].trigger('click')
    expect(wrapper.emitted('action')?.[0][0]).toEqual(first)
    wrapper.unmount()
  })
  it.each([{ fromSeat: 2, index: 0, source: '上家' }, { fromSeat: 1, index: 2, source: '下家' }])('puts the claimed pong tile on the $source side', ({ fromSeat, index, source }) => {
    const tiles = [tile, { ...tile, id: 'claimed' }, { ...tile, id: 'third' }]
    const wrapper = mount(MeldView, { props: { meld: { type: 'PONG', tiles, fromSeat, claimedTileId: 'claimed', concealed: false }, ownerSeat: 0 } })
    expect(wrapper.findAll('.ym-meld-slot')[index].attributes('data-tile-id')).toBe('claimed')
    expect(wrapper.findAll('.ym-meld-horizontal')).toHaveLength(1)
    expect(wrapper.attributes('aria-label')).toContain(`${source}供牌`)
  })
  it('lays the chi claim at the left and stacks the added kong above the original claim', () => {
    const tiles = [tile, { ...tile, id: 'claimed' }, { ...tile, id: 'third' }]
    const chi = mount(MeldView, { props: { meld: { type: 'CHI', tiles, fromSeat: 2, claimedTileId: 'claimed', concealed: false }, ownerSeat: 0 } })
    expect(chi.findAll('.ym-meld-slot')[0].attributes('data-tile-id')).toBe('claimed')
    const kong = mount(MeldView, { props: { meld: { type: 'KONG', tiles: [...tiles, { ...tile, id: 'added' }], fromSeat: 1, claimedTileId: 'claimed', concealed: false, added: true }, ownerSeat: 0 } })
    expect(kong.findAll('.ym-meld-slot')).toHaveLength(3)
    expect(kong.findAll('.ym-tile')).toHaveLength(4)
    expect(kong.find('.ym-meld-stack').attributes('data-tile-id')).toBe('claimed')
    expect(kong.findAll('.ym-meld-stack .ym-meld-horizontal')).toHaveLength(2)
  })
  it('shows concealed kongs as two backs and two faces with no claimed tile rotation', () => {
    const tiles = [0, 1, 2, 3].map(i => ({ ...tile, id: `concealed-${i}` }))
    const wrapper = mount(MeldView, { props: { meld: { type: 'KONG', tiles, fromSeat: 0, claimedTileId: '', concealed: true }, ownerSeat: 0 } })
    expect(wrapper.findAll('.ym-tile-back')).toHaveLength(2)
    expect(wrapper.findAll('.ym-meld-horizontal')).toHaveLength(0)
  })
  it('keeps acknowledged hand results visible and waits for the other players', () => {
    const me = player(); me.acknowledged = true
    const wrapper = mount(SettlementDialog, { props: { room: room({ result: result(), players: [me, player('p2', 1)], actions: [] }), busy: false } })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.find('.ym-result-footer').text()).toContain('已确认')
    expect(wrapper.find('.ym-result-footer button').attributes('disabled')).toBeDefined()
  })
  it('uses LEAVE, not ACK, for a match-ending result', async () => {
    const leave = { type: 'LEAVE', label: '退出', tileIds: [] }
    const wrapper = mount(SettlementDialog, { props: { room: room({ result: result({ matchOver: true }), actions: [{ type: 'ACK', label: '确认', tileIds: [] }, leave] }), busy: false } })
    await wrapper.find('.ym-result-footer button').trigger('click')
    expect(wrapper.emitted('action')?.[0][0]).toEqual(leave)
  })
})

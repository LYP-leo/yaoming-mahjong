import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TableView from './TableView.vue'
import RoomSidebar from './RoomSidebar.vue'
import SettlementDialog from './SettlementDialog.vue'
import { player, result, room, tile } from './testFixtures'

describe('drawn tile and intentional discards', () => {
  beforeEach(() => localStorage.clear())
  it('places the exact drawn physical tile separately, including replacement draws of the same kind', async () => {
    const me = player(); me.hand = [tile, { ...tile, id: 'replacement' }]; me.drawnTileId = 'replacement'
    const wrapper = mount(TableView, { props: { room: room({ players: [me] }), busy: false } })
    expect(wrapper.findAll('.ym-own-hand .ym-hand-tile')).toHaveLength(1)
    expect(wrapper.find('.ym-draw-slot button').attributes('aria-label')).toContain('刚摸到的')
    const next = { ...me, drawnTileId: null }
    await wrapper.setProps({ room: room({ players: [next] }) })
    expect(wrapper.find('.ym-draw-slot').exists()).toBe(true)
    expect(wrapper.find('.ym-draw-slot .ym-hand-tile').exists()).toBe(false)
    expect(wrapper.findAll('.ym-own-hand .ym-hand-tile')).toHaveLength(2)
    wrapper.unmount()
  })
  it('requires a second click or explicit confirmation by default', async () => {
    const wrapper = mount(TableView, { props: { room: room(), busy: false } })
    const hand = wrapper.find('.ym-hand-tile')
    await hand.trigger('click')
    expect(wrapper.emitted('action')).toBeUndefined()
    expect(hand.attributes('aria-pressed')).toBe('true')
    await wrapper.find('.ym-confirm-discard').trigger('click')
    expect(wrapper.emitted('action')?.[0][0]).toEqual(room().actions[0])
    wrapper.unmount()
  })
  it('confirms a selected tile with a second click but never writes while busy', async () => {
    const wrapper = mount(TableView, { props: { room: room(), busy: false } })
    const hand = wrapper.find('.ym-hand-tile')
    await hand.trigger('click'); await hand.trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
    await wrapper.setProps({ busy: true }); await hand.trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
    wrapper.unmount()
  })
  it('persists quick discard and only needs a single click after remount', async () => {
    const first = mount(TableView, { props: { room: room(), busy: false } })
    await first.find('.ym-play-settings input').setValue(true)
    expect(localStorage.getItem('yaoming.quickDiscard')).toBe('true')
    first.unmount()
    const second = mount(TableView, { props: { room: room(), busy: false } })
    await second.find('.ym-hand-tile').trigger('click')
    expect(second.emitted('action')).toHaveLength(1)
    second.unmount()
  })
  it('retains selected tiles across new versions with an unchanged action context', async () => {
    const wrapper = mount(TableView, { props: { room: room(), busy: false } })
    await wrapper.find('.ym-hand-tile').trigger('click')
    await wrapper.setProps({ room: room({ version: 5, message: '其他玩家已重新连接' }) })
    expect(wrapper.find('.ym-hand-tile').attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })
  it.each(['round', 'phase', 'draw', 'meld', 'trustee', 'legality'] as const)('resets selected tiles when %s changes, defaulting to a legal draw', async (kind) => {
    const wrapper = mount(TableView, { props: { room: room(), busy: false } })
    await wrapper.find('.ym-hand-tile').trigger('click')
    const next = room()
    if (kind === 'round') next.round++
    if (kind === 'phase') next.status = 'REACTION'
    if (kind === 'draw') next.players[0].drawnTileId = tile.id
    if (kind === 'meld') next.players[0].melds = [{ type: 'PONG', tiles: [], fromSeat: 1, claimedTileId: '', concealed: false }]
    if (kind === 'trustee') next.players[0].trustee = true
    if (kind === 'legality') next.actions = []
    await wrapper.setProps({ room: next })
    expect(wrapper.find('.ym-hand-tile').attributes('aria-pressed')).toBe(kind === 'draw' ? 'true' : 'false')
    wrapper.unmount()
  })
  it('marks the real last discard and never reinserts a claimed tile into the river', async () => {
    const me = player(); me.discards = [tile]
    const initial = room({ players: [me], lastDiscard: { tile, fromSeat: 0, claimed: false } })
    const wrapper = mount(TableView, { props: { room: initial, busy: false } })
    const sidebar = mount(RoomSidebar, { props: { room: initial, identity: null } })
    expect(wrapper.findAll('.ym-latest-river-tile')).toHaveLength(1)
    expect(wrapper.find('.ym-last-discard').exists()).toBe(false)
    expect(sidebar.find('.ym-last-discard').text()).toContain(me.name)
    const next = room({ players: [{ ...me, discards: [] }], lastDiscard: { tile, fromSeat: 0, claimed: true } })
    await wrapper.setProps({ room: next }); await sidebar.setProps({ room: next })
    expect(wrapper.findAll('.ym-river .ym-tile')).toHaveLength(0)
    expect(sidebar.find('.ym-last-discard').text()).toContain('已被取走')
    wrapper.unmount(); sidebar.unmount()
  })
  it('offers explicit recovery from timeout trustee without enabling manual discards', async () => {
    const me = player(); me.trustee = true; me.trusteeReason = 'TIMEOUT'
    const trustee = { type: 'TRUSTEE', label: '关闭托管', tileIds: [] }
    const wrapper = mount(TableView, { props: { room: room({ players: [me], actions: [...room().actions, trustee] }), busy: false } })
    expect(wrapper.find('.ym-trustee-banner').text()).toContain('操作超时')
    expect(wrapper.find('.ym-hand-tile').attributes('disabled')).toBeDefined()
    await wrapper.find('.ym-trustee-banner button').trigger('click')
    expect(wrapper.emitted('action')?.[0][0]).toEqual(trustee)
    wrapper.unmount()
  })
})

describe('server calibrated phase deadlines', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-07T12:05:00Z')); localStorage.clear() })
  afterEach(() => vi.useRealTimers())
  it.each(['2026-09-07T11:55:00Z', '2026-09-07T12:05:00Z'])('corrects local clock %s and recalibrates equal-version polls', async (localTime) => {
    vi.setSystemTime(new Date(localTime))
    const initial = room({ serverTime: '2026-09-07T12:00:00Z', deadlineAt: '2026-09-07T12:00:30Z', deadlineKind: 'DISCARD' })
    const wrapper = mount(RoomSidebar, { props: { room: initial, identity: null } })
    expect(wrapper.find('[role="timer"]').attributes('aria-label')).toBe('出牌剩余30秒')
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.find('[role="timer"]').attributes('aria-label')).toBe('出牌剩余25秒')
    await wrapper.setProps({ room: { ...initial, serverTime: '2026-09-07T12:00:07Z', clientReceivedAt: Date.now() } })
    expect(wrapper.find('[role="timer"]').attributes('aria-label')).toBe('出牌剩余23秒')
    wrapper.unmount()
  })
  it('does not reset the deadline after leaving the table view and reopening it', async () => {
    const initial = room({ serverTime: '2026-09-07T12:00:00Z', deadlineAt: '2026-09-07T12:00:30Z', deadlineKind: 'DISCARD' })
    const first = mount(RoomSidebar, { props: { room: initial, identity: null } })
    first.unmount()
    await vi.advanceTimersByTimeAsync(12000)
    const second = mount(RoomSidebar, { props: { room: { ...initial, serverTime: '2026-09-07T12:00:12Z', clientReceivedAt: Date.now() }, identity: null } })
    expect(second.find('[role="timer"]').attributes('aria-label')).toBe('出牌剩余18秒')
    second.unmount()
  })
  it('does not restart an old snapshot deadline when reopening the table while offline', async () => {
    const snapshot = room({ serverTime: '2026-09-07T12:00:00Z', deadlineAt: '2026-09-07T12:00:30Z', deadlineKind: 'DISCARD' })
    const first = mount(RoomSidebar, { props: { room: snapshot, identity: null } })
    expect(first.find('[role="timer"]').attributes('aria-label')).toBe('出牌剩余30秒')
    first.unmount()
    await vi.advanceTimersByTimeAsync(10000)
    const reopened = mount(RoomSidebar, { props: { room: snapshot, identity: null } })
    expect(reopened.find('[role="timer"]').attributes('aria-label')).toBe('出牌剩余20秒')
    reopened.unmount()
  })
  it('switches phase deadlines and disables expired response buttons without client-side advancement', async () => {
    const pass = { type: 'PASS', label: '过', tileIds: [] }
    const initial = room({ status: 'NEED_DRAW', serverTime: '2026-09-07T12:00:00Z', deadlineAt: '2026-09-07T12:00:15Z', deadlineKind: 'DRAW' })
    const wrapper = mount(TableView, { props: { room: initial, busy: false } })
    const sidebar = mount(RoomSidebar, { props: { room: initial, identity: null } })
    expect(sidebar.find('[role="timer"]').attributes('aria-label')).toBe('摸牌剩余15秒')
    expect(wrapper.find('.ym-mobile-deadline').text()).toContain('摸牌 15 秒')
    const reaction = room({ status: 'REACTION', actions: [pass], serverTime: '2026-09-07T12:00:00Z', deadlineAt: '2026-09-07T12:00:20Z', deadlineKind: 'REACTION' })
    await wrapper.setProps({ room: reaction }); await sidebar.setProps({ room: reaction })
    expect(sidebar.find('[role="timer"]').attributes('aria-label')).toBe('响应剩余20秒')
    expect(wrapper.find('.ym-mobile-deadline').text()).toContain('响应 20 秒')
    await vi.advanceTimersByTimeAsync(20000)
    await wrapper.find('.ym-action-bar button').trigger('click')
    expect(wrapper.emitted('action')).toBeUndefined()
    wrapper.unmount(); sidebar.unmount()
  })
  it('shows the shared settlement deadline after another player confirms without auto-ACKing or exiting', async () => {
    const initial = room({ status: 'HAND_END', result: result(), serverTime: '2026-09-07T12:00:00Z', deadlineAt: '2026-09-07T12:01:00Z', deadlineKind: 'SETTLEMENT', actions: [{ type: 'ACK', label: '确认', tileIds: [] }] })
    const wrapper = mount(SettlementDialog, { props: { room: initial, busy: false } })
    expect(wrapper.find('.ym-settlement-clock').text()).toContain('60 秒')
    await vi.advanceTimersByTimeAsync(10000)
    await wrapper.setProps({ room: { ...initial, version: 4, serverTime: '2026-09-07T12:00:10Z', clientReceivedAt: Date.now(), players: initial.players.map((p, i) => ({ ...p, acknowledged: i === 1 })) } })
    expect(wrapper.find('.ym-settlement-clock').text()).toContain('50 秒')
    await vi.advanceTimersByTimeAsync(50000)
    expect(wrapper.emitted('action')).toBeUndefined()
    await wrapper.setProps({ room: { ...initial, status: 'MATCH_END', result: result({ matchOver: true }), deadlineKind: null, deadlineAt: null } })
    expect(wrapper.find('.ym-settlement-clock').exists()).toBe(false)
    expect(wrapper.find('.ym-result-footer').text()).toContain('不会自动退出')
    wrapper.unmount()
  })
})

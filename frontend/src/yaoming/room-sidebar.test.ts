import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount } from '@vue/test-utils'
import RoomSidebar from './RoomSidebar.vue'
import { player, room, tile } from './testFixtures'
import type { Identity, RoomView } from './types'

const identity: Identity = { roomId: 'room1', playerId: 'p1', token: 'test-only-private-seat-code' }
const cleanup: Array<() => void> = []
function sidebar(value: RoomView = room(), currentIdentity: Identity | null = identity) {
  const wrapper = mount(RoomSidebar, { props: { room: value, identity: currentIdentity } })
  cleanup.push(() => wrapper.unmount())
  return wrapper
}

describe('compact public room sidebar', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-11T12:05:00Z')) })
  afterEach(() => { cleanup.splice(0).forEach(unmount => unmount()); vi.useRealTimers() })

  it('shows the round, wall, phase and current player without a hints panel', () => {
    const wrapper = sidebar(room({ roundLabel: '南2局', wallCount: 21, currentSeat: 1 }))
    expect(wrapper.classes()).toContain('ym-room-sidebar')
    expect(wrapper.find('.ym-round-card').text()).toContain('南2局')
    expect(wrapper.find('.ym-round-heading').text()).toContain('21')
    expect(wrapper.find('.ym-round-phase').text()).toBe('玩家2 · 等待出牌')
    expect(wrapper.find('[role="timer"]').exists()).toBe(false)
    expect(wrapper.find('.ym-hints').exists()).toBe(false)
    expect(wrapper.text()).toContain('三人 · 108 张')
    expect(wrapper.text()).toContain('4 番起和 · 8 番封顶')
    expect(wrapper.text()).toContain('10 点起步 · 东南六局')
  })

  it('emits rules, replay and waiting invitation without handling navigation itself', async () => {
    const wrapper = sidebar(room({ status: 'WAITING' }))
    await wrapper.find('.ym-sidebar-rules').trigger('click')
    await wrapper.find('.ym-replay-entry button').trigger('click')
    await wrapper.find('.ym-invite-card button').trigger('click')
    expect(wrapper.emitted('rules')).toEqual([[]])
    expect(wrapper.emitted('replay')).toEqual([[]])
    expect(wrapper.emitted('copy')).toEqual([['room1', '房间号']])
    await wrapper.setProps({ room: room() })
    expect(wrapper.find('.ym-invite-card').exists()).toBe(false)
  })

  it('renders only the public latest tile, source and physical discard kind', async () => {
    const opponent = player('p2', 1)
    opponent.hand = [{ ...tile, id: 'secret', label: 'PRIVATE_OPPONENT_HAND' }]
    const value = room({ players: [player(), opponent], lastDiscard: { tile, fromSeat: 1, claimed: false, kind: 'TSUMOGIRI' } })
    const wrapper = sidebar(value)
    expect(wrapper.find('.ym-last-discard').text()).toContain('玩家2 打出')
    expect(wrapper.find('.ym-last-discard').text()).toContain('最新弃牌')
    expect(wrapper.find('.ym-discard-tile').attributes('data-discard-kind')).toBe('TSUMOGIRI')
    expect(wrapper.find('.ym-discard-tile').attributes('aria-label')).toContain('摸切')
    expect(wrapper.html()).not.toContain('PRIVATE_OPPONENT_HAND')
    await wrapper.setProps({ room: { ...value, lastDiscard: { tile, fromSeat: 1, claimed: true, kind: 'TEDASHI' } } })
    expect(wrapper.find('.ym-last-discard').text()).toContain('已被取走')
    expect(wrapper.find('.ym-discard-tile').attributes('aria-label')).toContain('手切')
    expect(wrapper.find('.ym-discard-tsumogiri').exists()).toBe(false)
  })

  it('does not invent a discard or draw private concealed hands when no tile has been discarded', () => {
    const wrapper = sidebar(room({ lastDiscard: null }))
    expect(wrapper.find('.ym-last-discard').exists()).toBe(false)
    expect(wrapper.find('.ym-sidebar-no-discard').text()).toBe('尚无弃牌')
    expect(wrapper.find('svg').exists()).toBe(false)
  })

  it('calibrates server countdown, updates same-version snapshots and cleans up its interval', async () => {
    const value = room({ serverTime: '2026-09-11T12:00:00Z', deadlineAt: '2026-09-11T12:00:30Z', deadlineKind: 'DISCARD' })
    const wrapper = sidebar(value)
    expect(wrapper.find('[role="timer"]').attributes('aria-label')).toBe('出牌剩余30秒')
    expect(wrapper.find('[role="timer"]').attributes('aria-live')).toBe('off')
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.find('[role="timer"]').attributes('aria-label')).toBe('出牌剩余25秒')
    await wrapper.setProps({ room: { ...value, serverTime: '2026-09-11T12:00:10Z', clientReceivedAt: Date.now() } })
    expect(wrapper.find('[role="timer"]').attributes('aria-label')).toBe('出牌剩余20秒')
    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('reserves one silent countdown slot before and after a timed phase without recreating it', async () => {
    const wrapper = sidebar(room({ deadlineAt: null }))
    const slot = wrapper.get('.ym-round-clock').element
    expect(wrapper.get('.ym-round-clock').attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('.ym-round-clock').text()).toBe('')
    expect(wrapper.find('[role="timer"]').exists()).toBe(false)
    await wrapper.setProps({ room: room({ deadlineKind: 'DISCARD', deadlineAt: Date.now() + 30000 }) })
    expect(wrapper.get('.ym-round-clock').element).toBe(slot)
    expect(wrapper.get('.ym-round-clock').attributes('aria-hidden')).toBeUndefined()
    expect(wrapper.get('[role="timer"]').attributes('aria-label')).toBe('出牌剩余30秒')
    await wrapper.setProps({ room: room({ status: 'WAITING', deadlineAt: null }) })
    expect(wrapper.get('.ym-round-clock').element).toBe(slot)
    expect(wrapper.get('.ym-round-clock').text()).toBe('')
    expect(wrapper.find('[role="timer"]').exists()).toBe(false)
  })

  it('preserves full phase and discard names through ellipsis using text, title and accessible labels', () => {
    const name = '很长的玩家昵称'.repeat(30), players = [player(), { ...player('p2', 1), name }]
    const wrapper = sidebar(room({ players, currentSeat: 1, lastDiscard: { tile, fromSeat: 1, claimed: false } }))
    const phase = wrapper.get('.ym-round-phase'), discard = wrapper.get('.ym-sidebar-discard-copy strong')
    expect(phase.text()).toBe(`${name} · 等待出牌`)
    expect(phase.attributes('title')).toBe(phase.text())
    expect(phase.attributes('aria-label')).toBe(phase.text())
    expect(discard.text()).toBe(`${name} 打出`)
    expect(discard.attributes('title')).toBe(discard.text())
    expect(discard.attributes('aria-label')).toBe(discard.text())
  })

  it('keeps the fixed card slots equal for empty, short and long live content without inner scrollbars', async () => {
    const source = readFileSync(resolve(process.cwd(), 'src/yaoming/RoomSidebar.vue'), 'utf8')
    const styles = source.match(/<style scoped>([\s\S]*?)<\/style>/)?.[1]
    expect(styles).toBeDefined()
    expect(styles).not.toMatch(/overflow(?:-[xy])?:\s*(?:auto|scroll)/)
    const style = document.createElement('style'), host = document.createElement('div')
    style.textContent = styles!; host.className = 'ym-app'
    document.head.append(style); document.body.append(host)
    const wrapper = mount(RoomSidebar, { props: { room: room(), identity: null }, attachTo: host })
    cleanup.push(() => { wrapper.unmount(); host.remove(); style.remove() })
    const toolsCard = wrapper.get('.ym-sidebar-tools').element, clockSlot = wrapper.get('.ym-round-clock').element
    for (const count of [0, 1, 4, 5, 80]) {
      const name = count > 4 ? '超长昵称'.repeat(30) : '甲'
      const events = Array.from({ length: count }, (_, index) => ({ sequence: index + 1, text: `${name} 出牌记录 ${index + 1}` }))
      await wrapper.setProps({ room: room({ players: [{ ...player(), name }], events,
        lastDiscard: count ? { tile, fromSeat: 0, claimed: false } : null,
        deadlineAt: count ? Date.now() + 30000 : null, deadlineKind: count ? 'DISCARD' : null }) })
      expect(getComputedStyle(wrapper.get('.ym-round-phase').element).height).toBe('34px')
      expect(getComputedStyle(wrapper.get('.ym-round-clock').element).height).toBe('43px')
      expect(getComputedStyle(wrapper.get(count ? '.ym-sidebar-latest' : '.ym-sidebar-no-discard').element).height).toBe('58px')
      expect(getComputedStyle(wrapper.get(count ? '.ym-sidebar-recent' : '.ym-sidebar-record-empty').element).height).toBe('140px')
      expect(getComputedStyle(wrapper.get('.ym-sidebar-record-disclosure').element).minHeight).toBe('32px')
      for (const row of wrapper.findAll('.ym-sidebar-recent li')) {
        expect(getComputedStyle(row.element).height).toBe('35px')
        expect(row.attributes('title')).toBe(row.text())
      }
      expect(wrapper.get('.ym-sidebar-tools').element).toBe(toolsCard)
      expect(wrapper.get('.ym-round-clock').element).toBe(clockSlot)
    }
  })

  it('shows a zero response timer without emitting actions or advancing the game', async () => {
    const wrapper = sidebar(room({ status: 'REACTION', deadlineKind: 'REACTION', deadlineAt: Date.now() + 1000 }))
    await vi.advanceTimersByTimeAsync(1250)
    expect(wrapper.find('[role="timer"]').attributes('aria-label')).toBe('响应剩余0秒')
    expect(wrapper.find('.ym-round-clock').classes()).toContain('ym-urgent')
    expect(wrapper.find('.ym-round-clock').text()).toContain('正在同步牌局')
    expect(wrapper.emitted('action')).toBeUndefined()
    expect(wrapper.find('.ym-round-phase').text()).toBe('等待响应')
  })

  it('shows at most four recent records and expands the full log as normal content', async () => {
    const events = Array.from({ length: 9 }, (_, index) => ({ sequence: index + 1, text: `动作 ${index + 1}` }))
    const before = events.map(event => ({ ...event }))
    const wrapper = sidebar(room({ events }))
    expect(wrapper.findAll('.ym-sidebar-events li').map(line => line.text())).toEqual(['动作 9', '动作 8', '动作 7', '动作 6'])
    const details = wrapper.find<HTMLDetailsElement>('.ym-sidebar-full-records')
    details.element.open = true
    await details.trigger('toggle')
    expect(wrapper.find('.ym-sidebar-recent').exists()).toBe(false)
    expect(wrapper.findAll('.ym-sidebar-events li')).toHaveLength(9)
    expect(wrapper.findAll('.ym-sidebar-events li')[8].text()).toBe('动作 1')
    expect(events).toEqual(before)
    details.element.open = false
    await details.trigger('toggle')
    expect(wrapper.findAll('.ym-sidebar-events li')).toHaveLength(4)
  })

  it('keeps an empty record list minimal and omits unnecessary full-log details', () => {
    const wrapper = sidebar(room({ events: [] }))
    expect(wrapper.find('.ym-sidebar-record-empty').text()).toBe('等待第一位玩家行动。')
    expect(wrapper.find('.ym-sidebar-full-records').exists()).toBe(false)
  })

  it('returns to the short record list if a refreshed room has fewer than five records', async () => {
    const wrapper = sidebar(room({ events: Array.from({ length: 5 }, (_, index) => ({ sequence: index, text: `记录 ${index}` })) }))
    const details = wrapper.find<HTMLDetailsElement>('.ym-sidebar-full-records')
    details.element.open = true
    await details.trigger('toggle')
    await wrapper.setProps({ room: room({ events: [{ sequence: 9, text: '新记录' }] }) })
    expect(wrapper.find('.ym-sidebar-full-records').exists()).toBe(false)
    expect(wrapper.find('.ym-sidebar-recent').text()).toBe('新记录')
  })

  it('does not render the recovery credential until its details are opened', async () => {
    const wrapper = sidebar()
    expect(wrapper.html()).not.toContain(identity.token)
    expect(wrapper.find('.ym-recovery code').exists()).toBe(false)
    const details = wrapper.find<HTMLDetailsElement>('.ym-recovery')
    details.element.open = true
    await details.trigger('toggle')
    expect(wrapper.find('.ym-recovery code').text()).toBe(identity.token)
    await wrapper.find('.ym-recovery button').trigger('click')
    expect(wrapper.emitted('copy')).toEqual([[identity.token, '恢复码']])
    details.element.open = false
    await details.trigger('toggle')
    expect(wrapper.html()).not.toContain(identity.token)
  })

  it.each([null, { ...identity, roomId: 'different-room' }, { ...identity, playerId: 'different-seat' }])('omits recovery for a missing or mismatched identity %#', currentIdentity => {
    const wrapper = sidebar(room(), currentIdentity)
    expect(wrapper.find('.ym-recovery').exists()).toBe(false)
    expect(wrapper.html()).not.toContain(identity.token)
    expect(wrapper.emitted('copy')).toBeUndefined()
  })

  it('closes and removes the credential when the active identity changes', async () => {
    const wrapper = sidebar()
    const details = wrapper.find<HTMLDetailsElement>('.ym-recovery')
    details.element.open = true
    await details.trigger('toggle')
    await wrapper.setProps({ identity: { ...identity, token: 'next-test-only-code' } })
    expect(wrapper.find<HTMLDetailsElement>('.ym-recovery').element.open).toBe(false)
    expect(wrapper.html()).not.toContain('next-test-only-code')
    expect(wrapper.html()).not.toContain(identity.token)
  })

  it('uses the actual server wind for the opening seat after the dealer rotates', () => {
    const players = [player(), player('p2', 1), player('p3', 2)]
    players[0].wind = '西'; players[1].wind = '东'; players[2].wind = '南'
    const wrapper = sidebar(room({ dealerSeat: 1, players, dice: { opening: [2, 3, 4], breaking: [1, 2, 3], openingSeat: 1, breakStack: 6 } }))
    expect(wrapper.find('.ym-sidebar-dice').text()).toContain('第一次：2 · 3 · 4')
    expect(wrapper.find('.ym-sidebar-dice').text()).toContain('第二次：1 · 2 · 3')
    expect(wrapper.find('.ym-sidebar-dice').text()).toContain('开门位置：东位 · 第 6 墩切墙')
  })
})

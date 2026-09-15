import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TableView from './TableView.vue'
import RoomSidebar from './RoomSidebar.vue'
import YaomingApp from './YaomingApp.vue'
import { useYaomingStore } from './store'
import { player, room, tile } from './testFixtures'
import type { Action, RoomView } from './types'

const wrappers: VueWrapper[] = []
const keep = <T extends VueWrapper>(wrapper: T): T => { wrappers.push(wrapper); return wrapper }
const trustee: Action = { type: 'TRUSTEE', label: '切换托管', tileIds: [] }
const longName = '这是一位带有极长昵称的玩家'.repeat(12)
function latestRoom(claimed = false): RoomView {
  return room({ status: 'REACTION', players: [player(), { ...player('p2', 1), name: longName }, player('p3', 2)],
    lastDiscard: { tile, fromSeat: 1, claimed, kind: 'TSUMOGIRI' } })
}
beforeEach(() => { vi.restoreAllMocks(); vi.useFakeTimers(); localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia()) })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers() })

describe('non-scrolling compact latest discard', () => {
  it.each([false, true])('keeps full accessible long names and an independent status line when claimed=%s', claimed => {
    const wrapper = keep(mount(RoomSidebar, { props: { room: latestRoom(claimed), identity: null } }))
    const latest = wrapper.get('.ym-last-discard')
    expect(latest.get('.ym-sidebar-discard-copy strong').text()).toBe(`${longName} 打出`)
    expect(latest.get('.ym-sidebar-discard-copy strong').attributes('title')).toBe(`${longName} 打出`)
    expect(latest.get('.ym-sidebar-discard-copy strong').attributes('aria-label')).toBe(`${longName} 打出`)
    expect(latest.get('.ym-sidebar-discard-copy span').text()).toBe(claimed ? '已被取走' : '最新弃牌')
    expect(latest.get('.ym-sidebar-discard-copy').element.children).toHaveLength(2)
    expect(latest.get('.ym-discard-tile').classes()).toContain('ym-discard-tsumogiri')
    expect(latest.get('.ym-discard-tile').attributes('aria-label')).toBe('一条 · 摸切')
    expect(latest.findAll('.ym-discard-tile svg.mahjong-art')).toHaveLength(1)
  })

  it('places the latest discard in a natural-flow sidebar rather than a tiny table-center scroller', () => {
    const view = latestRoom()
    const sidebar = keep(mount(RoomSidebar, { props: { room: view, identity: null } }))
    const table = keep(mount(TableView, { props: { room: view, busy: false } }))
    expect(sidebar.findAll('.ym-round-card .ym-last-discard')).toHaveLength(1)
    expect(table.find('.ym-last-discard, .ym-table-center').exists()).toBe(false)
    const source = readFileSync(resolve(process.cwd(), 'src/yaoming/RoomSidebar.vue'), 'utf8')
    expect(source).toMatch(/\.ym-app \.ym-room-sidebar \.ym-sidebar-latest \{[^}]*height: 58px;[^}]*overflow: visible;/)
    expect(source).toMatch(/\.ym-app \.ym-room-sidebar \.ym-sidebar-discard-copy strong \{[^}]*white-space: nowrap;[^}]*overflow: hidden;[^}]*text-overflow: ellipsis;/)
    expect(source).not.toMatch(/overflow(?:-[xy])?:\s*(?:auto|scroll)/)
  })

  it('keeps complete logs behind an explicit disclosure instead of reinstating small inner scrollbars', async () => {
    const view = room({ ...latestRoom(), events: Array.from({ length: 20 }, (_, sequence) => ({ sequence, text: `第 ${sequence + 1} 条记录` })) })
    const sidebar = keep(mount(RoomSidebar, { props: { room: view, identity: null } }))
    expect(sidebar.findAll('.ym-sidebar-recent li')).toHaveLength(4)
    expect(sidebar.find('[aria-label="完整牌桌记录"]').exists()).toBe(false)
    const disclosure = sidebar.get('details.ym-sidebar-full-records')
    ;(disclosure.element as HTMLDetailsElement).open = true
    await disclosure.trigger('toggle')
    expect(sidebar.findAll('[aria-label="完整牌桌记录"] li')).toHaveLength(20)
    const source = readFileSync(resolve(process.cwd(), 'src/yaoming/RoomSidebar.vue'), 'utf8')
    expect(source).toMatch(/\.ym-app \.ym-sidebar-events \{[^}]*height: auto;[^}]*max-height: none;[^}]*overflow: visible;/)
  })

  it('retains the tile and sidebar card DOM through claimed, name and known-kind updates', async () => {
    const view = latestRoom(), wrapper = keep(mount(RoomSidebar, { props: { room: view, identity: null } }))
    const card = wrapper.get('.ym-round-card').element, latest = wrapper.get('.ym-last-discard').element
    const discarded = wrapper.get('.ym-discard-tile').element
    await wrapper.setProps({ room: { ...view, version: 2, players: view.players.map(p => ({ ...p, name: '短昵称' })), lastDiscard: { ...view.lastDiscard!, claimed: true, kind: 'TEDASHI' } } })
    expect(wrapper.get('.ym-round-card').element).toBe(card)
    expect(wrapper.get('.ym-last-discard').element).toBe(latest)
    expect(wrapper.get('.ym-discard-tile').element).toBe(discarded)
    expect(wrapper.get('.ym-sidebar-discard-copy span').text()).toBe('已被取走')
    expect(wrapper.get('.ym-discard-tile').classes()).not.toContain('ym-discard-tsumogiri')
  })
})

describe('human trustee copy without a client-side strategy', () => {
  it.each(['MANUAL', 'OFFLINE', 'TIMEOUT'] as const)('explains %s as pure tsumogiri and never submits an automatic table action', async reason => {
    const me = { ...player(), trustee: true, trusteeReason: reason }
    const wrapper = keep(mount(TableView, { props: { room: room({ players: [me], actions: [trustee] }), busy: false } }))
    const banner = wrapper.get('.ym-trustee-banner')
    expect(banner.text()).toContain('自动摸切')
    expect(banner.attributes('aria-label')).toContain('响应一律过，不吃碰杠、不自动和牌')
    expect(banner.attributes('title')).toContain('按手牌排序打出最右一张合法牌')
    expect(wrapper.get('.ym-own-info').text()).toContain('自动摸切')
    expect(wrapper.get('.ym-hand-tile').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(2000)
    expect(wrapper.emitted('action')).toBeUndefined()
    await banner.get('button').trigger('click')
    expect(wrapper.emitted('action')).toEqual([[trustee]])
    await wrapper.setProps({ busy: true }); await banner.get('button').trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
  })

  it('does not relabel a practice robot as a pure-tsumogiri human trustee', () => {
    const robot = { ...player('p2', 1), bot: true, trustee: true }
    const human = { ...player('p3', 2), trustee: true }
    const wrapper = keep(mount(TableView, { props: { room: room({ players: [player(), robot, human] }), busy: false } }))
    expect(wrapper.get('[aria-label="玩家2 的区域"] .ym-lane-identity').text()).toContain('机器人')
    expect(wrapper.get('[aria-label="玩家2 的区域"] .ym-lane-identity').text()).not.toContain('自动摸切')
    expect(wrapper.get('[aria-label="玩家3 的区域"] .ym-lane-identity').text()).toContain('自动摸切')
  })

  function app() {
    const store = useYaomingStore()
    store.identity = { roomId: 'room1', playerId: 'p1', token: 'local-test-not-a-real-seat' }
    store.room = room({ actions: [...room().actions, trustee] }); store.connected = true
    vi.spyOn(store, 'start').mockResolvedValue(); vi.spyOn(store, 'stop').mockImplementation(() => {})
    const act = vi.spyOn(store, 'act').mockResolvedValue(true)
    const wrapper = keep(mount(YaomingApp, { global: { stubs: { HintsPanel: true, TableView: true } } }))
    return { store, wrapper, act }
  }

  it('keeps familiar toggle names while explaining policy accessibly and sending only the server action', async () => {
    const { wrapper, store, act } = app()
    const toggle = wrapper.get('.ym-trustee-toggle')
    expect(toggle.text()).toBe('开启托管')
    expect(toggle.attributes('title')).toContain('开启托管自动摸切')
    expect(toggle.attributes('aria-label')).toContain('不吃碰杠、不自动和牌')
    expect(toggle.attributes('aria-label')).toContain('最右一张合法牌')
    await toggle.trigger('click'); expect(act).toHaveBeenCalledExactlyOnceWith(trustee)
    store.room = { ...store.room!, players: store.room!.players.map(p => ({ ...p, trustee: p.id === 'p1' })) }
    await wrapper.vm.$nextTick()
    expect(toggle.text()).toBe('取消托管')
    expect(toggle.attributes('title')).toContain('取消托管，收回控制')
    store.busy = true; await wrapper.vm.$nextTick(); await toggle.trigger('click')
    expect(act).toHaveBeenCalledTimes(1)
  })

  it('preserves intelligent-robot takeover on explicit leave but explains offline retained-seat tsumogiri', async () => {
    const { wrapper, act } = app()
    await wrapper.findAll('button').find(button => button.text() === '离开房间')!.trigger('click')
    expect(wrapper.get('[aria-labelledby="ym-leave-title"]').text()).toContain('对局中离开会交由机器人继续')
    await wrapper.findAll('button').find(button => button.text() === '留在牌桌')!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '暂离 · 返回大厅')!.trigger('click')
    expect(wrapper.get('[aria-labelledby="ym-detach-title"]').text()).toContain('离线超过 60 秒后托管自动摸切，不吃碰杠、不自动和牌')
    expect(act).not.toHaveBeenCalled()
  })
})

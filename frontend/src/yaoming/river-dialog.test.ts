import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import RiverDialog from './RiverDialog.vue'
import DiscardTile from './DiscardTile.vue'
import TableView from './TableView.vue'
import { player, room, tile } from './testFixtures'
import type { Player, RoomView } from './types'

const wrappers: VueWrapper[] = []
const hosts: HTMLElement[] = []
const keep = <T extends VueWrapper>(wrapper: T): T => { wrappers.push(wrapper); return wrapper }
function host() { const element = document.createElement('div'); document.body.append(element); hosts.push(element); return element }
function longRiver(count = 28): Player {
  const owner = player('p2', 1)
  owner.discards = Array.from({ length: count }, (_, index) => ({ ...tile, id: `river-${index}`, rank: index % 9 + 1, label: `${index % 9 + 1}条` }))
  owner.discardKinds = Object.fromEntries(owner.discards.map((discard, index) => [discard.id, index % 2 ? 'TEDASHI' : 'TSUMOGIRI']))
  return owner
}
function tableRoom(overrides: Partial<RoomView> = {}): RoomView {
  return room({ players: [player(), longRiver(), player('p3', 2)], ...overrides })
}
const inspectButton = (wrapper: VueWrapper) => wrapper.get<HTMLButtonElement>('[data-player-id="p2"] .ym-lane-river-more')

beforeEach(() => localStorage.clear())
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  hosts.splice(0).forEach(element => element.remove())
})

describe('full river read-only dialog', () => {
  it('shows all public physical tiles in chronological order without sorting or reading concealed hands', () => {
    const owner = longRiver(31)
    owner.hand = [{ ...tile, id: 'secret-hand-id', label: 'PRIVATE_CONCEALED_TILE' }]
    const original = JSON.stringify(owner)
    const wrapper = keep(mount(RiverDialog, { props: { player: owner } }))
    expect(wrapper.get('[role="dialog"]').attributes('aria-modal')).toBe('true')
    expect(wrapper.get('h2').text()).toContain('南家 · 玩家2 的牌河')
    expect(wrapper.findAll('.ym-full-river li')).toHaveLength(31)
    expect(wrapper.findAllComponents(DiscardTile).map(card => card.props('tile').id)).toEqual(owner.discards.map(discard => discard.id))
    expect(wrapper.findAll('.ym-full-river small').map(label => label.text())).toEqual(Array.from({ length: 31 }, (_, index) => `${index + 1}`))
    expect(wrapper.html()).not.toContain('PRIVATE_CONCEALED_TILE')
    expect(wrapper.html()).not.toContain('secret-hand-id')
    expect(JSON.stringify(owner)).toBe(original)
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('keeps known hand/draw provenance and latest markers while never reinserting a claimed tile', async () => {
    const owner = longRiver(4), latest = owner.discards[2]
    const wrapper = keep(mount(RiverDialog, { props: { player: owner, lastDiscard: { tile: latest, fromSeat: owner.seat, claimed: false, kind: 'TSUMOGIRI' } } }))
    expect(wrapper.findAll('.ym-discard-tsumogiri')).toHaveLength(2)
    expect(wrapper.findAll('[data-discard-kind="TEDASHI"]')).toHaveLength(2)
    expect(wrapper.findAll('.ym-latest-river-tile')).toHaveLength(1)
    expect(wrapper.get('.ym-latest-river-tile').attributes('aria-label')).toContain('摸切')
    await wrapper.setProps({ player: { ...owner, discards: owner.discards.filter(discard => discard.id !== latest.id) },
      lastDiscard: { tile: latest, fromSeat: owner.seat, claimed: true, kind: 'TSUMOGIRI' } })
    expect(wrapper.findAllComponents(DiscardTile).map(card => card.props('tile').id)).not.toContain(latest.id)
    expect(wrapper.findAll('.ym-latest-river-tile')).toHaveLength(0)
    expect(wrapper.findAll('.ym-full-river li')).toHaveLength(3)
  })

  it('does not guess unknown history from the newest tile or a matching drawn entity', () => {
    const owner = longRiver(2); owner.discardKinds = null; owner.drawnTileId = owner.discards[0].id
    const wrapper = keep(mount(RiverDialog, { props: { player: owner, lastDiscard: { tile: owner.discards[0], fromSeat: owner.seat, claimed: false } } }))
    expect(wrapper.findAll('[data-discard-kind]')).toHaveLength(0)
    expect(wrapper.findAll('.ym-discard-tsumogiri')).toHaveLength(0)
    expect(wrapper.get('.ym-latest-river-tile').attributes('aria-label')).toBe('1条')
  })

  it('closes from Escape, the close button and the backdrop but not from content clicks', async () => {
    const wrapper = keep(mount(RiverDialog, { props: { player: longRiver() } }))
    await wrapper.get('.ym-full-river li').trigger('click')
    await wrapper.get('h2').trigger('click')
    expect(wrapper.emitted('close')).toBeUndefined()
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    await wrapper.get('button[aria-label="关闭完整牌河"]').trigger('click')
    await wrapper.get('.ym-river-overlay').trigger('click')
    expect(wrapper.emitted('close')).toEqual([[], [], []])
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('focuses the dialog, traps Tab in its only control and restores the connected opener', async () => {
    const element = host(), opener = document.createElement('button')
    opener.textContent = '查看全部牌河'; element.append(opener); opener.focus()
    const wrapper = keep(mount(RiverDialog, { attachTo: element, props: { player: longRiver() } }))
    const dialog = wrapper.get<HTMLElement>('[role="dialog"]'), close = wrapper.get<HTMLButtonElement>('button')
    expect(document.activeElement).toBe(dialog.element)
    for (const shiftKey of [false, true]) {
      const event = new KeyboardEvent('keydown', { key: 'Tab', shiftKey, bubbles: true, cancelable: true })
      dialog.element.dispatchEvent(event)
      expect(event.defaultPrevented).toBe(true)
      expect(document.activeElement).toBe(close.element)
    }
    wrapper.unmount()
    expect(document.activeElement).toBe(opener)
  })

  it('renders an empty river and clearly says automatic draws pause but the game clock continues', () => {
    const wrapper = keep(mount(RiverDialog, { props: { player: longRiver(0) } }))
    expect(wrapper.findAll('.ym-full-river li')).toHaveLength(0)
    expect(wrapper.text()).toContain('尚未出牌')
    expect(wrapper.get('.ym-river-reminder').text()).toContain('暂停自动摸牌')
    expect(wrapper.get('.ym-river-reminder').text()).toContain('倒计时仍会继续')
  })

  it('uses historical-only guidance in replay and retains the live reminder when returning to a live table', async () => {
    const owner = longRiver(28)
    const wrapper = keep(mount(RiverDialog, { props: { player: owner, readonly: true } }))
    const list = wrapper.get('.ym-full-river').element
    expect(wrapper.get('.ym-river-reminder').text()).toBe('只读历史牌河，展示所选复盘步骤的弃牌记录。')
    expect(wrapper.text()).not.toMatch(/暂停自动摸牌|倒计时仍会继续/)
    expect(wrapper.findAll('.ym-full-river li')).toHaveLength(28)
    expect(wrapper.emitted('action')).toBeUndefined()
    await wrapper.setProps({ readonly: false })
    expect(wrapper.get('.ym-full-river').element).toBe(list)
    expect(wrapper.get('.ym-river-reminder').text()).toContain('暂停自动摸牌')
  })
})

describe('table river inspection and parent automatic-draw pause signal', () => {
  it('forwards readonly mode into a historical full-river dialog', async () => {
    const wrapper = keep(mount(TableView, { props: { room: tableRoom(), busy: false, readonly: true } }))
    await inspectButton(wrapper).trigger('click')
    expect(wrapper.get('.ym-river-reminder').text()).toContain('只读历史牌河')
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('emits pause on opening and release on Escape while restoring focus and never submitting a game action', async () => {
    const wrapper = keep(mount(TableView, { attachTo: host(), props: { room: tableRoom(), busy: false } }))
    const opener = inspectButton(wrapper); opener.element.focus(); await opener.trigger('click')
    expect(wrapper.emitted('overlay')).toEqual([[true]])
    expect(wrapper.findAll('.ym-full-river li')).toHaveLength(28)
    expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.emitted('overlay')).toEqual([[true], [false]])
    expect(document.activeElement).toBe(opener.element)
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('releases pause after backdrop close and close-button close', async () => {
    const wrapper = keep(mount(TableView, { props: { room: tableRoom(), busy: false } }))
    await inspectButton(wrapper).trigger('click')
    await wrapper.get('.ym-river-overlay').trigger('click')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await inspectButton(wrapper).trigger('click')
    await wrapper.get('button[aria-label="关闭完整牌河"]').trigger('click')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.emitted('overlay')).toEqual([[true], [false], [true], [false]])
  })

  it.each(['room', 'round', 'me'] as const)('closes inspection and releases pause on %s context change', async change => {
    const initial = tableRoom()
    const wrapper = keep(mount(TableView, { props: { room: initial, busy: false } }))
    await inspectButton(wrapper).trigger('click')
    const next = { ...initial, ...(change === 'room' ? { id: 'other-room' } : change === 'round' ? { round: 2 } : { meId: 'p3' }) }
    await wrapper.setProps({ room: next })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.emitted('overlay')).toEqual([[true], [false]])
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('preserves the overlay and pause across an ordinary update while showing newly appended public discards', async () => {
    const initial = tableRoom()
    const wrapper = keep(mount(TableView, { props: { room: initial, busy: false } }))
    await inspectButton(wrapper).trigger('click')
    const dialog = wrapper.get('[role="dialog"]').element
    const latest = { ...tile, id: 'new-public-discard' }
    await wrapper.setProps({ room: { ...initial, version: 2, status: 'REACTION',
      players: initial.players.map(owner => owner.id === 'p2' ? { ...owner, discards: [...owner.discards, latest],
        discardKinds: { ...owner.discardKinds, [latest.id]: 'TSUMOGIRI' } } : owner),
      lastDiscard: { tile: latest, fromSeat: 1, claimed: false, kind: 'TSUMOGIRI' } } })
    expect(wrapper.get('[role="dialog"]').element).toBe(dialog)
    expect(wrapper.findAll('.ym-full-river li')).toHaveLength(29)
    expect(wrapper.get('.ym-full-river .ym-latest-river-tile').attributes('aria-label')).toContain('摸切')
    expect(wrapper.emitted('overlay')).toEqual([[true]])
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('releases pause if the inspected player disappears or the table unmounts', async () => {
    const initial = tableRoom()
    const notifications: boolean[] = []
    const wrapper = keep(mount(TableView, { props: { room: initial, busy: false, onOverlay: (open: boolean) => notifications.push(open) } }))
    await inspectButton(wrapper).trigger('click')
    await wrapper.setProps({ room: { ...initial, players: initial.players.filter(owner => owner.id !== 'p2') } })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.emitted('overlay')).toEqual([[true], [false]])
    await wrapper.setProps({ room: initial })
    await inspectButton(wrapper).trigger('click')
    wrapper.unmount()
    expect(notifications).toEqual([true, false, true, false])
  })
})

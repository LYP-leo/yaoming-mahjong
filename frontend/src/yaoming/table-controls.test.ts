import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { readFileSync } from 'node:fs'
import TableView from './TableView.vue'
import { player, room, tile, result } from './testFixtures'

let wrapper: VueWrapper
beforeEach(() => localStorage.clear())
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })
async function key(key: string) { document.body.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })); await nextTick() }
describe('full width controls and keyboard integration', () => {
  it('keeps every reaction accessible without carousel arrows or instructions', () => {
    const actions = Array.from({ length: 12 }, (_, i) => ({ type: 'KONG', tileIds: [tile.id], label: `杠 ${i}` }))
    actions.push({ type: 'PASS', tileIds: [], label: '过' })
    wrapper = mount(TableView, { props: { room: room({ status: 'REACTION', actions }), busy: false } })
    expect(wrapper.findAll('.ym-action-bar button')).toHaveLength(13)
    expect(wrapper.findAll('.ym-actions-scroll')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('再次点击或按出牌按钮')
    expect(wrapper.find('.ym-action-bar').attributes('style')).toContain('--ym-action-rows: 4')
  })
  it('selects in rendered order including the physical draw and sends once on Enter', async () => {
    const tiles = [{ ...tile, id: 'high', rank: 9 }, { ...tile, id: 'low', rank: 1 }, { ...tile, id: 'draw', rank: 5 }]
    const me = { ...player(), hand: tiles, drawnTileId: 'draw' }
    const actions = tiles.map(t => ({ type: 'DISCARD', tileIds: [t.id], label: '出牌' }))
    wrapper = mount(TableView, { attachTo: document.body, props: { room: room({ players: [me], actions }), busy: false } })
    await key('d'); expect(wrapper.find('.ym-hand-tile-selected').attributes('data-hand-tile-id')).toBe('low')
    await key('a'); expect(wrapper.find('.ym-hand-tile-selected').attributes('data-hand-tile-id')).toBe('draw')
    await key('Enter'); await key('Enter')
    expect(wrapper.emitted('action')).toEqual([[actions[2]]])
  })
  it('passes a response with Space but leaves busy and trustee games alone', async () => {
    const pass = { type: 'PASS', tileIds: [], label: '过' }
    wrapper = mount(TableView, { attachTo: document.body, props: { room: room({ status: 'REACTION', actions: [pass] }), busy: true } })
    await key(' '); expect(wrapper.emitted('action')).toBeUndefined()
    await wrapper.setProps({ busy: false }); await key(' ')
    expect(wrapper.emitted('action')).toEqual([[pass]])
    await wrapper.setProps({ room: room({ version: 2, status: 'REACTION', players: [{ ...player(), trustee: true }], actions: [pass] }) })
    await key(' '); expect(wrapper.emitted('action')).toHaveLength(1)
  })
  it('blocks hotkeys behind the full river dialog', async () => {
    const me = { ...player(), discards: Array.from({ length: 30 }, (_, i) => ({ ...tile, id: `r-${i}` })) }
    wrapper = mount(TableView, { attachTo: document.body, props: { room: room({ players: [me] }), busy: false } })
    await wrapper.find('.ym-lane-river-heading button').trigger('click')
    await key('d'); await key('Enter')
    expect(wrapper.find('.ym-hand-tile-selected').exists()).toBe(false)
    expect(wrapper.emitted('action')).toBeUndefined()
  })
  it('renders replay hands without live controls or hotkey actions', async () => {
    wrapper = mount(TableView, { attachTo: document.body, props: { room: room({ result: result() }), readonly: true, busy: false } })
    expect(wrapper.findAll('.ym-player-lane .ym-vector-tile')).not.toHaveLength(0)
    expect(wrapper.find('.ym-play-settings input').exists()).toBe(false)
    expect(wrapper.find('.ym-confirm-discard').exists()).toBe(false)
    await key('d'); await key('Enter'); await key(' ')
    expect(wrapper.emitted('action')).toBeUndefined()
    await wrapper.find('.ym-action-bar button').trigger('click')
    expect(wrapper.emitted('result')).toHaveLength(1)
  })
  it('keeps own draw slot across draw/discard updates', async () => {
    wrapper = mount(TableView, { props: { room: room({ players: [{ ...player(), drawnTileId: tile.id }] }), busy: false } })
    const slot = wrapper.find('.ym-draw-slot').element
    await wrapper.setProps({ room: room({ version: 2, players: [{ ...player(), hand: [], handSize: 0 }] }) })
    expect(wrapper.find('.ym-draw-slot').element).toBe(slot)
    expect(wrapper.find('.ym-draw-slot button').exists()).toBe(false)
  })
  it('uses fixed full-width action tracks and contains no arrow implementation', () => {
    const component = readFileSync('src/yaoming/TableView.vue', 'utf8')
    const css = readFileSync('src/yaoming/stable-controls.css', 'utf8')
    expect(component).not.toMatch(/scrollActions|actionsOverflow|ym-actions-scroll/)
    expect(css).toMatch(/\.ym-action-bar \{ grid-column: 1 \/ -1; grid-row: 3;/)
    expect(css).not.toMatch(/overflow-x: auto/)
  })
})

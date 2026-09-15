import { h } from 'vue'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import TableView from './TableView.vue'
import WaitTiles from './WaitTiles.vue'
import { player, room, tile } from './testFixtures'
import type { Action, Player, RoomView, RuleId } from './types'

const rules = ['yaoming-3p', 'yaoming-4p'] as const
const trustee: Action = { type: 'TRUSTEE', label: '收回控制', tileIds: [] }
const wrappers: VueWrapper[] = []
const keep = <T extends VueWrapper>(wrapper: T) => { wrappers.push(wrapper); return wrapper }
const minimum = (ruleId: RuleId) => ruleId === 'yaoming-4p' ? 3 : 4
const summary = (ruleId: RuleId) => `${minimum(ruleId)}番起和，8番封顶；无振听，打过或放过同种牌仍可点和`
function view(ruleId: RuleId, overrides: Partial<RoomView> = {}) {
  const count = ruleId === 'yaoming-4p' ? 4 : 3
  return room({ ruleId, capacity: count, players: Array.from({ length: count }, (_, seat) => ({ ...player(`p${seat + 1}`, seat), wind: ['东', '南', '西', '北'][seat] })), ...overrides })
}
const render = (value: RoomView, props: { readonly?: boolean; autoDrawNotice?: string } = {}) => keep(mount(TableView, { props: { room: value, busy: false, ...props } }))
beforeEach(() => { localStorage.clear(); sessionStorage.clear() })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })

describe('only contextual eligibility copy at the private hand panel', () => {
  it.each(rules.flatMap(ruleId => (['MANUAL', 'OFFLINE', 'TIMEOUT', null] as const).map(reason => [ruleId, reason] as const)))('hides every notice while self is managed in %s (%s), keeping recovery usable', async (ruleId, reason) => {
    const value = view(ruleId, { actions: [trustee], winHint: `尚未成和牌形，至少需要${minimum(ruleId)}番` })
    value.players[0] = { ...value.players[0], trustee: true, trusteeReason: reason }
    const wrapper = render(value, { autoDrawNotice: '自动摸牌失败，请手动摸牌' })
    expect(wrapper.find('.ym-panel-notices .ym-win-hint').exists()).toBe(false)
    const banner = wrapper.get('.ym-trustee-banner')
    expect(banner.text()).toContain('自动摸切')
    expect(banner.text()).toContain('收回控制')
    expect(wrapper.get('.ym-panel-notices').text()).not.toContain('自动摸牌失败')
    await banner.get('button').trigger('click')
    expect(wrapper.emitted('action')).toEqual([[trustee]])
    await wrapper.setProps({ busy: true }); await banner.get('button').trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
  })

  it.each(rules.flatMap(ruleId => ['plain', 'spaced', 'ascii', 'final-period'].map(format => [ruleId, format] as const)))('hides exactly the static summary in %s (%s)', (ruleId, format) => {
    let text = summary(ruleId)
    if (format === 'spaced') text = ` \n ${minimum(ruleId)} 番起和， 8 番封顶； 无振听，打过或放过同种牌仍可点和 \t`
    if (format === 'ascii') text = text.replaceAll('，', ',').replaceAll('；', ';')
    if (format === 'final-period') text += '。'
    const wrapper = render(view(ruleId, { winHint: text }))
    expect(wrapper.find('.ym-win-hint').exists()).toBe(false)
    expect(wrapper.get('.ym-panel-notices').text()).toBe('')
  })

  it.each(rules.flatMap(ruleId => ['', '   ', '\n\t　'].map(text => [ruleId, text] as const)))('keeps a blank server hint blank for %s: %j', (ruleId, text) => {
    const wrapper = render(view(ruleId, { winHint: text }))
    expect(wrapper.find('.ym-win-hint').exists()).toBe(false)
    expect(wrapper.get('.ym-panel-notices').text()).toBe('')
    expect(wrapper.text()).not.toContain('和牌资格由服务器判定')
  })

  it.each(rules.flatMap(ruleId => [
    `尚未成和牌形，至少需要${minimum(ruleId)}番`,
    `已成和牌形，当前仅2番，至少需要${minimum(ruleId)}番`,
    `可自摸：${minimum(ruleId)}番`,
    '可自摸：10番（按8番结算）',
    `合法牌型 · ${minimum(ruleId)} 番可点和`,
    `点和不足 ${minimum(ruleId)} 番`,
    '吃碰后请出牌，本次不能自摸或开杠',
    `${summary(ruleId)}；当前手牌可自摸：5番`,
  ].map(text => [ruleId, text] as const)))('preserves actual server analysis in %s: %s', (ruleId, text) => {
    const wrapper = render(view(ruleId, { winHint: text }))
    const hint = wrapper.get('.ym-win-hint')
    expect(hint.text()).toBe(text)
    expect(hint.attributes('title')).toBe(text)
  })

  it.each(rules)('does not hide the local analysis merely because an opponent is managed (%s)', ruleId => {
    const value = view(ruleId, { winHint: '可自摸：5番' }); value.players[1].trustee = true
    const wrapper = render(value)
    expect(wrapper.get('.ym-win-hint').text()).toBe('可自摸：5番')
    expect(wrapper.find('.ym-trustee-banner').exists()).toBe(false)
  })

  it.each(rules)('retains actionable auto-draw failures for an unmanaged live player in %s', ruleId => {
    const wrapper = render(view(ruleId, { winHint: summary(ruleId) }), { autoDrawNotice: '自动摸牌失败，请手动摸牌' })
    expect(wrapper.findAll('.ym-win-hint')).toHaveLength(1)
    expect(wrapper.get('.ym-win-hint').text()).toBe('自动摸牌失败，请手动摸牌')
    expect(wrapper.get('.ym-win-hint').attributes('role')).toBe('status')
  })

  it.each(rules.flatMap(ruleId => [false, true].map(managed => [ruleId, managed] as const)))('shows no live notice or trustee banner in readonly %s (managed=%s)', (ruleId, managed) => {
    const value = view(ruleId, { winHint: '可自摸：5番', actions: [trustee] }); value.players[0].trustee = managed
    const wrapper = render(value, { readonly: true, autoDrawNotice: '自动摸牌失败，请手动摸牌' })
    expect(wrapper.find('.ym-win-hint').exists()).toBe(false)
    expect(wrapper.find('.ym-trustee-banner').exists()).toBe(false)
    expect(wrapper.get('.ym-panel-notices').text()).toBe('')
  })

  it.each(rules)('keeps reserved panel and hint-tile DOM while notice visibility changes (%s)', async ruleId => {
    const value = view(ruleId, { winHint: '可自摸：5番', actions: [trustee] })
    const wrapper = keep(mount(TableView, { props: { room: value, busy: false }, slots: { hints: () => h(WaitTiles, { waits: [
      { tile, unseenCount: 1, canRon: true, ronFan: minimum(ruleId), canTsumo: true, tsumoFan: minimum(ruleId), ronReason: '' },
    ] }) } }))
    const panel = wrapper.get('.ym-player-panel').element, notices = wrapper.get('.ym-panel-notices').element
    const hints = wrapper.get('.ym-hand-hints').element, tileNode = wrapper.get('.ym-hint-tile .mahjong-art').element
    const tracks = [...panel.children].map(child => child.className)
    const hintHtml = wrapper.get('.ym-hand-hints').html()
    const update = (hint: string, managed = false): RoomView => ({ ...value, winHint: hint,
      players: value.players.map((p, i): Player => i === 0 ? { ...p, trustee: managed, trusteeReason: managed ? 'MANUAL' : null } : p) })
    for (const next of [update(summary(ruleId)), update(''), update('可自摸：5番', true), update('尚未成和牌形'), update('可自摸：5番')]) {
      await wrapper.setProps({ room: next })
      expect(wrapper.get('.ym-player-panel').element).toBe(panel)
      expect(wrapper.get('.ym-panel-notices').element).toBe(notices)
      expect(wrapper.get('.ym-hand-hints').element).toBe(hints)
      expect(wrapper.get('.ym-hint-tile .mahjong-art').element).toBe(tileNode)
      expect([...panel.children].map(child => child.className)).toEqual(tracks)
      expect(wrapper.get('.ym-hand-hints').html()).toBe(hintHtml)
    }
    expect(wrapper.get('.ym-win-hint').text()).toBe('可自摸：5番')
  })
})

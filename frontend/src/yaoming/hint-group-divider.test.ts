import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import HintsPanel from './HintsPanel.vue'
import { yaomingApi } from './store'
import { room, tile } from './testFixtures'
import type { HintResponse, Identity } from './types'

const identity: Identity = { roomId: 'room1', playerId: 'p1', token: 'divider-test-token' }
const groupSelector = '.ym-app .ym-hand-hints .ym-discard-hint'
const dividerSelector = `${groupSelector} + .ym-discard-hint::before`
const wrappers: VueWrapper[] = [], hosts: HTMLElement[] = [], styles: HTMLStyleElement[] = []
const response = (count: number, version = 1): HintResponse => ({ roomId: 'room1', playerId: 'p1', version,
  analysis: { mode: 'DISCARD', note: '', waits: [], discards: Array.from({ length: count }, (_, index) => ({
    tile: { ...tile, id: `discard-${index}`, rank: index + 1, label: `${index + 1}条` },
    waits: [5, 6].map(rank => ({ tile: { ...tile, id: `wait-${rank}`, rank, label: `${rank}条` },
      unseenCount: 1, canRon: true, ronFan: 4, ronReason: '', canTsumo: true, tsumoFan: 4 })),
  })) },
})
function stylesheet() {
  const style = document.createElement('style')
  style.textContent = readFileSync(resolve(process.cwd(), 'src/yaoming/player-lanes.css'), 'utf8')
  document.head.append(style); styles.push(style)
  return [...style.sheet!.cssRules].filter((rule): rule is CSSStyleRule => 'selectorText' in rule)
}
function panel() {
  const host = document.createElement('div'), slot = document.createElement('div')
  host.className = 'ym-app'; slot.className = 'ym-hand-hints'; host.append(slot); document.body.append(host); hosts.push(host)
  const wrapper = mount(HintsPanel, { props: { room: room(), identity }, attachTo: slot })
  wrappers.push(wrapper); return { wrapper, host }
}
async function advance() { await vi.advanceTimersByTimeAsync(200); await flushPromises() }
beforeEach(() => { vi.restoreAllMocks(); vi.useFakeTimers() })
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount()); hosts.splice(0).forEach(host => host.remove())
  styles.splice(0).forEach(style => style.remove()); vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks()
})

describe('decorative dividers between discard-to-wait groups', () => {
  it('anchors an empty, non-interactive absolute pseudo-element in the existing gap without adding layout space', () => {
    const rules = stylesheet(), base = rules.find(rule => rule.selectorText === groupSelector)
    const dividers = rules.filter(rule => rule.selectorText.includes('ym-discard-hint') && rule.selectorText.includes('::before'))
    expect(dividers.map(rule => rule.selectorText)).toEqual([dividerSelector])
    expect(base?.style.position).toBe('relative')
    const decoration = dividers[0].style
    expect(decoration.position).toBe('absolute'); expect(['""', "''"]).toContain(decoration.content)
    expect(decoration.left).toBe('-11px'); expect(decoration.top).toBe('7px'); expect(decoration.bottom).toBe('7px')
    expect(decoration.width).toBe('1px'); expect(decoration.getPropertyValue('pointer-events')).toBe('none')
    expect(decoration.getPropertyValue('background')).toBe('#d7bf7b88')
    for (const property of ['margin', 'padding', 'border', 'height', 'min-width', 'min-height']) expect(decoration.getPropertyValue(property)).toBe('')
    const content = rules.find(rule => rule.selectorText === '.ym-app .ym-hand-hints .ym-hints-content')
    expect(content?.style.gap).toBe('22px')
  })

  it.each([0, 1, 3])('matches only the spaces between %s actual groups, never the first group or its wait tiles', async count => {
    stylesheet(); vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: response(count) })
    const { wrapper, host } = panel(); await advance()
    const groups = wrapper.findAll('.ym-discard-hint'), decorated = [...host.querySelectorAll(dividerSelector.replace('::before', ''))]
    expect(groups).toHaveLength(count)
    expect(decorated).toEqual(groups.slice(1).map(group => group.element))
    for (const group of groups) {
      expect(group.element.children).toHaveLength(3)
      expect(group.findAll('.ym-hint-tile')).toHaveLength(2)
    }
    expect(wrapper.findAll('hr,[role="separator"]')).toHaveLength(0)
    const visible = wrapper.element.cloneNode(true) as Element
    visible.querySelectorAll('svg title,svg desc').forEach(node => node.remove())
    expect(visible.textContent?.trim()).toBe('1张4番1张4番'.repeat(count))
  })

  it('preserves all decorated group nodes while an ordinary refresh is pending and when its displayed result is unchanged', async () => {
    let finish!: (value: { data: HintResponse }) => void
    const pending = new Promise<{ data: HintResponse }>(accept => { finish = accept })
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response(3) }).mockReturnValueOnce(pending as never)
    const { wrapper } = panel(); await advance()
    const groups = wrapper.findAll('.ym-discard-hint').map(group => group.element)
    const faces = wrapper.findAll('svg.mahjong-art').map(face => face.element)
    await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(wrapper.findAll('.ym-discard-hint').map(group => group.element)).toEqual(groups)
    finish({ data: response(3, 2) }); await flushPromises()
    expect(wrapper.findAll('.ym-discard-hint').map(group => group.element)).toEqual(groups)
    expect(wrapper.findAll('svg.mahjong-art').map(face => face.element)).toEqual(faces)
  })
})

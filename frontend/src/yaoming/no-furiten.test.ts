import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import RulesPage from './RulesPage.vue'
import TableView from './TableView.vue'
import HintsPanel from './HintsPanel.vue'
import { yaomingApi } from './store'
import { player, room, tile } from './testFixtures'
import type { HintResponse, Rules } from './types'

const wrappers: VueWrapper[] = []
const keep = <T extends VueWrapper>(wrapper: T): T => { wrappers.push(wrapper); return wrapper }
const rules: Rules = { name: '要命麻将', version: 'test', description: '', notes: ['点和必须满足合法牌型和 4 番门槛。'], fans: [], tiles: [tile] }
const legacyPlayer = () => ({ ...player(), discardedCodes: ['B1'], passedCodes: ['B1'], discards: [{ ...tile, id: 'previous-B1' }] })
beforeEach(() => { vi.restoreAllMocks(); localStorage.clear(); vi.useFakeTimers() })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers() })

describe('no-furiten rule presentation', () => {
  it.each([null, rules])('states both removed restrictions and retains structure/fan/window requirements with rules=%s', value => {
    const wrapper = keep(mount(RulesPage, { props: { rules: value } }))
    const text = wrapper.text()
    expect(text).toContain('没有舍牌振听或过和振听')
    expect(text).toContain('自己打过、曾放过的同种牌，在后续响应窗口仍可点和，无需等自己摸牌')
    expect(text).toContain('点和仍须符合合法和牌结构并达到 4 番')
    expect(text).not.toMatch(/自己打过的同种牌不能点和|同种牌暂时不能点和|自己下次摸牌解除|自摸不受这两项限制/)
    expect(wrapper.findAll('.ym-rule-overview article')).toHaveLength(4)
    expect(wrapper.findAll('.ym-rule-section')).toHaveLength(4)
  })

  it('keeps obsolete restriction explanations out of local hint and count fixtures', () => {
    for (const file of ['dev/HintsPreview.vue', 'study.test.ts', 'wait-tiles.test.ts']) {
      const source = readFileSync(resolve(process.cwd(), 'src/yaoming', file), 'utf8')
      expect(source).not.toMatch(/ronReason:\s*['"][^'"\r\n]*(?:打过|舍牌限制|摸牌解除|振听)/)
    }
  })
})

describe('server-authoritative eligibility with legacy history fields', () => {
  it('does not suppress a legal server WIN because the same kind was discarded or passed before', async () => {
    const win = { type: 'WIN', label: '点和', tileIds: [] }
    const wrapper = keep(mount(TableView, { props: { room: room({ status: 'REACTION', players: [legacyPlayer()], actions: [win], winHint: '合法牌型 · 4 番可点和' }), busy: false } }))
    const button = wrapper.get('.ym-action-bar button')
    expect(button.text()).toBe('点和')
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    expect(wrapper.emitted('action')).toEqual([[win]])
  })

  it('does not invent WIN when the server reports an insufficient-fan response window', () => {
    const wrapper = keep(mount(TableView, { props: { room: room({ status: 'REACTION', players: [legacyPlayer()], actions: [{ type: 'PASS', label: '过', tileIds: [] }], winHint: '点和不足 4 番' }), busy: false } }))
    expect(wrapper.get('.ym-win-hint').text()).toBe('点和不足 4 番')
    expect(wrapper.findAll('.ym-action-bar button').map(button => button.text())).toEqual(['过'])
  })

  it('retains a server-eligible zero-count wait despite legacy discard/pass fields, without visible rule text or a game action', async () => {
    const identity = { roomId: 'room1', playerId: 'p1', token: 'local-no-furiten-fixture' }
    const data: HintResponse = { roomId: identity.roomId, playerId: identity.playerId, version: 1, analysis: { mode: 'WAIT', note: '', discards: [],
      waits: [{ tile, unseenCount: 0, canTsumo: true, tsumoFan: 4, canRon: true, ronFan: 4, ronReason: '' }] } }
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data })
    const post = vi.spyOn(yaomingApi, 'post')
    const wrapper = keep(mount(HintsPanel, { props: { identity, room: room({ status: 'NEED_DRAW', players: [legacyPlayer()] }) } }))
    await vi.advanceTimersByTimeAsync(200); await flushPromises()
    expect(wrapper.findAll('.ym-hint-waits .ym-tile')).toHaveLength(1)
    expect(wrapper.get('.ym-hint-count').text()).toBe('0张')
    expect(wrapper.get('.ym-hint-count').attributes('aria-label')).toContain('不是牌墙剩余张数')
    expect(wrapper.get('.ym-hint-fans').text()).toBe('4番')
    expect(wrapper.text()).not.toMatch(/振听|点和|进张|打过/)
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(post).not.toHaveBeenCalled()
    expect(wrapper.emitted('action')).toBeUndefined()
  })
})

import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import WaitTiles from './WaitTiles.vue'
import TileView from './TileView.vue'
import type { WaitHint } from './types'

const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals() })
function wait(unseenCount: number, extra: Partial<WaitHint> = {}): WaitHint {
  return { tile: { id: 'hint-D7', suit: 'DOTS', rank: 7, label: '七筒' }, unseenCount,
    canTsumo: true, tsumoFan: 4, canRon: true, ronFan: 4, ronReason: '', ...extra }
}
function render(waits: WaitHint[]) {
  const wrapper = mount(WaitTiles, { props: { waits } })
  wrappers.push(wrapper); return wrapper
}
function visibleText(wrapper: VueWrapper) {
  const copy = wrapper.element.cloneNode(true) as Element
  copy.querySelectorAll('svg title,svg desc').forEach(node => node.remove())
  return copy.textContent?.trim()
}

describe('quiet waiting-count and fan presentation', () => {
  it.each([0, 1, 2, 3, 4])('puts exact %i张 and the corresponding fan below the real TileView and outside its SVG', count => {
    const wrapper = render([wait(count)])
    const tile = wrapper.getComponent(TileView), displayed = wrapper.get('.ym-hint-count')
    expect(tile.props('small')).toBe(true)
    expect(tile.element.parentElement).toBe(wrapper.get('.ym-hint-tile').element)
    expect(tile.element.nextElementSibling).toBe(displayed.element)
    expect(displayed.text()).toBe(`${count}张`)
    expect(displayed.element.closest('svg')).toBeNull()
    expect(wrapper.get('.ym-hint-fans').text()).toBe('4番')
    expect(wrapper.get('.ym-hint-fans').element.closest('svg')).toBeNull()
    expect(visibleText(wrapper)).toBe(`${count}张4番`)
    expect(wrapper.findAll('button,input,select,h1,h2,h3,h4,p,details,summary')).toHaveLength(0)
  })

  it('retains a zero-count face with an accessible count definition but no visible explanation', () => {
    const wrapper = render([wait(0)])
    expect(wrapper.findAll('li')).toHaveLength(1)
    expect(wrapper.get('li').classes()).toContain('ym-hint-zero')
    expect(wrapper.find('.ym-hint-exhausted').exists()).toBe(false)
    const label = wrapper.get('.ym-hint-count').attributes('aria-label')
    expect(label).toContain('七筒尚未可见 0 张')
    expect(label).toContain('可能在对手手中')
    expect(label).toContain('不是牌墙剩余张数')
    expect(wrapper.get('.ym-hint-count').attributes('title')).toContain('不是牌墙剩余张数')
    expect(visibleText(wrapper)).toBe('0张4番')
    expect(wrapper.get('li').classes()).not.toContain('ym-hint-structural')
  })

  it('shows only the allowed self-draw fan, without an invalid ron fan or explanation', () => {
    const wrapper = render([wait(1, { canRon: false, ronFan: 3, ronReason: '点和不足 4 番' })])
    expect(wrapper.findAll('li')).toHaveLength(1)
    expect(wrapper.get('.ym-hint-count').text()).toBe('1张')
    expect(wrapper.get('li').classes()).not.toContain('ym-hint-structural')
    expect(wrapper.get('li').classes()).not.toContain('ym-hint-zero')
    expect(wrapper.find('.ym-hint-exhausted').exists()).toBe(false)
    expect(wrapper.find('.ym-hint-wait-info').exists()).toBe(false)
    expect(visibleText(wrapper)).toBe('1张自摸4番'); expect(wrapper.text()).not.toMatch(/点和|3番|门槛/)
    expect(wrapper.get('.ym-hint-fans').attributes('aria-label')).toBe('七筒，自摸 4 番')
    expect(wrapper.findAll('button,input,select')).toHaveLength(0)
  })

  it.each([
    [{ canRon: true, ronFan: 4, canTsumo: true, tsumoFan: 4 }, ['4番'], '七筒，点和 4 番，自摸 4 番'],
    [{ canRon: true, ronFan: 4, canTsumo: true, tsumoFan: 5 }, ['点和4番', '自摸5番'], '七筒，点和 4 番，自摸 5 番'],
    [{ canRon: true, ronFan: 8, canTsumo: false, tsumoFan: 3 }, ['点和8番'], '七筒，点和 8 番'],
    [{ canRon: false, ronFan: 3, canTsumo: true, tsumoFan: 8 }, ['自摸8番'], '七筒，自摸 8 番'],
  ] as const)('renders server-authorized fan variants %o accessibly', (fan, labels, description) => {
    const wrapper = render([wait(1, fan)])
    expect(wrapper.findAll('.ym-hint-fans > span').map(line => line.text())).toEqual(labels)
    expect(wrapper.get('.ym-hint-fans').attributes('aria-label')).toBe(description)
    expect(wrapper.get('.ym-hint-fans').attributes('title')).toBe(description)
  })

  it('updates fan text and eligibility without replacing the face or count elements', async () => {
    const wrapper = render([wait(1)]), face = wrapper.getComponent(TileView).element, count = wrapper.get('.ym-hint-count').element
    await wrapper.setProps({ waits: [wait(1, { tsumoFan: 5 })] })
    expect(wrapper.findAll('.ym-hint-fans > span').map(line => line.text())).toEqual(['点和4番', '自摸5番'])
    await wrapper.setProps({ waits: [wait(1, { canRon: false, ronFan: 3, tsumoFan: 5 })] })
    expect(wrapper.get('.ym-hint-fans').text()).toBe('自摸5番')
    expect(wrapper.getComponent(TileView).element).toBe(face); expect(wrapper.get('.ym-hint-count').element).toBe(count)
  })

  it('is completely silent when supplied no candidates', () => {
    const wrapper = render([])
    expect(wrapper.findAll('li')).toHaveLength(0); expect(wrapper.text()).toBe('')
  })

  it('updates the shared list without leaving stale zero counts or warnings after new analysis', async () => {
    const wrapper = render([wait(0)]), row = wrapper.get('li').element, tile = wrapper.getComponent(TileView).element
    await wrapper.setProps({ waits: [wait(1)] })
    expect(wrapper.findAll('li')).toHaveLength(1)
    expect(wrapper.get('li').element).toBe(row); expect(wrapper.getComponent(TileView).element).toBe(tile)
    expect(wrapper.get('.ym-hint-count').text()).toBe('1张')
    expect(wrapper.get('li').classes()).not.toContain('ym-hint-zero')
    expect(wrapper.find('.ym-hint-exhausted').exists()).toBe(false)
    await wrapper.setProps({ waits: [] })
    expect(wrapper.findAll('li')).toHaveLength(0)
    expect(wrapper.text()).toBe('')
  })

  it('keeps the same face element when only the representative physical tile ID changes', async () => {
    const wrapper = render([wait(1)]), tile = wrapper.getComponent(TileView).element
    await wrapper.setProps({ waits: [wait(1, { tile: { ...wait(1).tile, id: 'replacement-id' } })] })
    expect(wrapper.getComponent(TileView).element).toBe(tile); expect(visibleText(wrapper)).toBe('1张4番')
  })

  it('uses the supplied unseen count without reading secret state, mutating hints or fetching game data', () => {
    const secretRead = vi.fn(() => { throw new Error('Private state must not be read by the display component') })
    const entry = wait(1)
    Object.defineProperties(entry, { wall: { get: secretRead }, opponents: { get: secretRead }, identity: { get: secretRead } })
    Object.freeze(entry.tile); Object.freeze(entry)
    const fetch = vi.fn(() => { throw new Error('WaitTiles must remain presentation-only') })
    vi.stubGlobal('fetch', fetch)
    const wrapper = render([entry])
    expect(wrapper.get('.ym-hint-count').text()).toBe('1张')
    expect(wrapper.getComponent(TileView).props('tile')).toEqual(entry.tile)
    expect(entry.unseenCount).toBe(1)
    expect(secretRead).not.toHaveBeenCalled(); expect(fetch).not.toHaveBeenCalled()
    expect(wrapper.emitted()).toEqual({})
  })

  it('reserves a fixed area with one scrolling axis and fixed space for both fan lines', () => {
    const css = readFileSync(resolve('src/yaoming/study.css'), 'utf8')
    const tileColumn = css.match(/\.ym-app \.ym-hint-tile\s*\{([^}]+)\}/)![1]
    expect(tileColumn).toContain('flex-direction: column')
    expect(tileColumn).toContain('align-items: center')
    expect(tileColumn).toContain('flex: 0 0 50px')
    const area = css.match(/\.ym-app \.ym-hints\s*\{([^}]+)\}/)![1]
    expect(area).toContain('height: 184px'); expect(area).toContain('min-height: 184px'); expect(area).toContain('max-height: 184px')
    const content = css.match(/\.ym-app \.ym-hints-content\s*\{([^}]+)\}/)![1]
    expect(content).toContain('overflow-x: hidden'); expect(content).toContain('overflow-y: auto')
    expect(content).toContain('box-sizing: border-box')
    const fans = css.match(/\.ym-app \.ym-hint-fans\s*\{([^}]+)\}/)![1]
    expect(fans).toContain('height: 28px'); expect(fans).toContain('line-height: 14px')
    expect(css).not.toContain('.ym-hint-wait-info')
    expect(tileColumn).not.toMatch(/(?:height|transform|background-image):/)
  })
})

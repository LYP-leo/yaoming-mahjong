import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

const originalLocation = location.pathname + location.search + location.hash
afterEach(() => { history.replaceState({}, '', originalLocation) })

describe('new-game-only application entry', () => {
  it.each(['/', '/?legacy=1', '/?legacy=0', '/?legacy=true', '/?room=123456&legacy=1', '/?legacy=1#rules'])('always renders YaomingApp at %s', url => {
    history.replaceState({}, '', url)
    const wrapper = mount(App, { global: { stubs: { YaomingApp: { template: '<main data-testid="new-game">要命麻将</main>' } } } })
    expect(wrapper.findAll('[data-testid="new-game"]')).toHaveLength(1)
    expect(wrapper.text()).toBe('要命麻将')
    expect(wrapper.find('.ym-legacy-switch').exists()).toBe(false)
    wrapper.unmount()
  })

  it('contains no alternate component import, query switch, or event-based old-game route', () => {
    const source = readFileSync(resolve('src/App.vue'), 'utf8')
    expect(source).toContain("import YaomingApp from './yaoming/YaomingApp.vue'")
    expect(source).not.toMatch(/LegacyApp|defineAsyncComponent|URLSearchParams|@legacy|\bv-if\b/)
  })

  it('removes old-only pages, API, state and styles without removing shared new-game artwork', () => {
    for (const file of ['LegacyApp.vue', 'api.ts', 'types.ts', 'seat.ts', 'seat.test.ts', 'stores/game.ts', 'stores/game.test.ts', 'style.css', 'components/RuleCenter.vue', 'components/TileSetEditor.vue', 'components/MahjongGlyph.vue', 'tileGlyphs.ts', 'tileGlyphs.test.ts']) {
      expect(existsSync(resolve('src', file)), file).toBe(false)
    }
    for (const file of ['yaoming/YaomingApp.vue', 'yaoming/store.ts', 'yaoming/types.ts', 'components/MahjongTileFace.vue', 'tileArtwork.ts']) {
      expect(existsSync(resolve('src', file)), file).toBe(true)
    }
    const entry = readFileSync(resolve('src/main.ts'), 'utf8')
    expect(entry).toContain("import './base.css'"); expect(entry).not.toContain("import './style.css'")
    const base = readFileSync(resolve('src/base.css'), 'utf8')
    expect(base).toMatch(/body\s*\{[^}]*margin:\s*0/)
    expect(base).not.toMatch(/(?:^|\n)header\s*\{|fonts\.googleapis|\.shell\b|\.river\b|\.tile\b/)
  })

  it('has no STOMP or SockJS dependency in either manifest and keeps the artwork preview independent of deleted pages', () => {
    for (const file of ['package.json', 'package-lock.json']) {
      expect(readFileSync(resolve(file), 'utf8'), file).not.toMatch(/@stomp\/stompjs|sockjs-client/)
    }
    const preview = readFileSync(resolve('src/tileArtPreview.ts'), 'utf8')
    expect(preview).not.toMatch(/TileSetEditor|RuleCenter|LegacyApp|旧版选牌/)
    expect(preview).toContain("import MahjongTileFace from './components/MahjongTileFace.vue'")
    expect(preview).toContain("import MeldView from './yaoming/MeldView.vue'")
  })
})

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = (name: string) => readFileSync(resolve(process.cwd(), `src/yaoming/${name}`), 'utf8')
const playability = source('playability.css')
const mobile = playability.slice(playability.indexOf('@media (max-width: 640px)'))
const narrow = playability.slice(playability.indexOf('@media (max-width: 380px)'))
const settlement = source('settlement.css')
const base = source('yaoming.css')
const study = source('study.css')

// These inspect declared CSS contracts, not rendered geometry. Native scrollbar
// thickness, fractional pixels, wrapping and clipping are checked separately in
// the isolated browser previews; jsdom does not implement layout.
function declarations(css: string, selector: string): Record<string, string> {
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '')
  const block = [...clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .find(match => match[1].split(',').some(part => part.trim() === selector))?.[2]
  expect(block, `Missing CSS rule ${selector}`).toBeDefined()
  return Object.fromEntries((block || '').split(';').filter(part => part.includes(':')).map(part => {
    const colon = part.indexOf(':')
    return [part.slice(0, colon).trim(), part.slice(colon + 1).trim()]
  }))
}
function px(value: string): number {
  if (value === '0') return 0
  expect(value).toMatch(/^\d+(?:\.\d+)?px$/)
  return Number(value.slice(0, -2))
}
const rowSizes = (rule: Record<string, string>) => rule['grid-template-rows'].split(' ').map(px)
function verticalPadding(rule: Record<string, string>): number {
  const sides = rule.padding.split(' ').map(px)
  return sides[0] + (sides.length < 3 ? sides[0] : sides[2])
}
const desktopPanel = () => declarations(playability, '.ym-app .ym-player-panel')
const mobilePanel = () => declarations(mobile, '.ym-app .ym-player-panel')

describe('small-scrollbar regression CSS contracts', () => {
  it('uses normal page scrolling on very short phones instead of a nearly fitting fixed dock', () => {
    const short = playability.slice(playability.indexOf('@media (max-width: 640px) and (max-height: 540px)'))
    expect(declarations(short, '.ym-app .ym-player-panel').position).toBe('static')
    expect(declarations(short, '.ym-app .ym-player-panel')['max-height']).toBe('none')
    expect(declarations(short, '.ym-app .ym-play-layout')['padding-bottom']).toBe('0')
  })
  it('balances the desktop panel fixed tracks, gaps and padding without a rounding overflow allowance', () => {
    const panel = desktopPanel(), rows = rowSizes(panel)
    expect(rows).toHaveLength(5)
    expect(rows.reduce((total, row) => total + row, 0) + px(panel.gap) * (rows.length - 1) + verticalPadding(panel)).toBe(px(panel.height))
    expect(panel['box-sizing']).toBe('border-box')
  })

  it('balances the mobile panel and gives its safe-area inset equal height and padding contributions', () => {
    const panel = mobilePanel(), rows = rowSizes(panel)
    const height = panel.height.match(/^calc\((\d+)px \+ (env\(safe-area-inset-bottom, 0px\))\)$/)
    const padding = panel.padding.match(/^(\d+)px \d+px calc\((\d+)px \+ (env\(safe-area-inset-bottom, 0px\))\)$/)
    expect(height).not.toBeNull()
    expect(padding).not.toBeNull()
    expect(padding![3]).toBe(height![2])
    expect(panel['border-top']).toMatch(/^1px solid /)
    expect(rows.reduce((total, row) => total + row, 0) + px(desktopPanel().gap) * (rows.length - 1) + Number(padding![1]) + Number(padding![2]) + 1).toBe(Number(height![1]))
    expect(panel['max-height']).toBe('60dvh')
    expect(panel['overflow-y']).toBe('auto')
  })

  it.each([
    ['desktop', playability, 96, 66, 18],
    ['mobile', mobile, 84, 52, 20],
  ] as const)('budgets %s hand height plus padding and a thin scrollbar inside the hand track', (_name, css, expectedTrack, expectedHand, expectedPadding) => {
    const hand = declarations(css, '.ym-app .ym-player-panel .ym-own-hand')
    const scroll = declarations(css, '.ym-app .ym-hand-scroll')
    const track = rowSizes(declarations(css, '.ym-app .ym-player-panel'))[1]
    expect(track).toBe(expectedTrack)
    expect(px(hand.height)).toBe(expectedHand)
    expect(verticalPadding(scroll)).toBe(expectedPadding)
    // 11px is the browser QA budgeting case, not a guarantee about every OS.
    expect(px(hand.height) + verticalPadding(scroll) + 11).toBeLessThanOrEqual(track)
    expect(declarations(playability, '.ym-app .ym-hand-scroll')['scrollbar-width']).toBe('thin')
    expect(declarations(playability, '.ym-app .ym-hand-scroll')['max-height']).toBe('100%')
  })

  it('budgets two complete mobile river rows plus the marker padding without a few-pixel overflow', () => {
    const table = { ...declarations(playability, '.ym-app .ym-table'), ...declarations(mobile, '.ym-app .ym-table') }
    const river = declarations(playability, '.ym-app .ym-table .ym-river')
    const rowGap = px(declarations(base, '.ym-app .ym-river').gap)
    const needed = px(river['grid-auto-rows']) * 2 + rowGap + verticalPadding(river)
    const opponentRiver = px(table['--ym-opponents-height']) - px(table['--ym-seat-height']) - px(table['--ym-toggle-height']) - px(table['--ym-backs-height']) - px(table['--ym-melds-height']) - px(table['--ym-river-label-height']) - 3 * px(declarations(playability, '.ym-app .ym-opponent-detail').gap)
    expect(needed).toBe(84)
    expect(opponentRiver).toBe(86)
    expect(opponentRiver).toBeGreaterThanOrEqual(needed)
    expect(px(table['--ym-own-river-height'])).toBeGreaterThanOrEqual(needed)
  })

  it('budgets the ordinary trustee banner and a one-line qualification inside the fixed notice track', () => {
    const banner = declarations(playability, '.ym-app .ym-panel-notices .ym-trustee-banner')
    const compact = declarations(base, '.ym-app .ym-compact')
    const hint = declarations(playability, '.ym-app .ym-panel-notices .ym-win-hint')
    const hintFont = px(declarations(base, '.ym-app .ym-win-hint')['font-size'])
    const bannerHeight = px(compact['min-height']) + verticalPadding(banner) + 2 + px(banner.margin.split(' ')[2])
    const qualificationHeight = hintFont * Number(hint['line-height']) + verticalPadding(hint)
    expect(bannerHeight + qualificationHeight).toBeLessThanOrEqual(rowSizes(desktopPanel())[2])
    expect(declarations(playability, '.ym-app .ym-panel-notices')['overflow-y']).toBe('auto')
  })

  it.each([
    '.ym-app .ym-table .ym-river',
    '.ym-app .ym-panel-notices',
    '.ym-app .ym-side-card .ym-live-message',
    '.ym-app .ym-events',
  ])('limits %s to its intended vertical axis', selector => {
    const rule = declarations(playability, selector)
    expect(rule['overflow-x']).toBe('hidden')
    expect(rule['overflow-y']).toBe('auto')
    expect(rule['scrollbar-width']).toBe('thin')
  })

  it.each([
    '.ym-app .ym-table .ym-meld-strip',
    '.ym-app .ym-player-panel .ym-action-bar',
  ])('limits %s to its intended horizontal axis', selector => {
    const rule = declarations(playability, selector)
    expect(rule['overflow-x']).toBe('auto')
    expect(rule['overflow-y']).toBe('hidden')
    expect(rule['scrollbar-width']).toBe('thin')
  })

  it('keeps the short settings row non-scrolling and omits only its repeated explanation on narrow screens', () => {
    const settings = declarations(playability, '.ym-app .ym-player-panel .ym-play-settings')
    expect(settings.overflow).toBeUndefined()
    expect(settings['overflow-x']).toBeUndefined()
    expect(settings['overflow-y']).toBeUndefined()
    expect(declarations(narrow, '.ym-app .ym-play-settings label span').display).toBe('none')
    expect(narrow).not.toMatch(/\.ym-play-settings\s+label\s*\{[^}]*display:\s*none/)
  })

  it('lets entry and settings dialogs own vertical scrolling instead of their overlay', () => {
    expect(declarations(playability, '.ym-app .ym-overlay').overflow).toBe('hidden')
    const dialog = declarations(playability, '.ym-app .ym-entry-dialog')
    expect(dialog['overflow-x']).toBe('hidden')
    expect(dialog['overflow-y']).toBe('auto')
    expect(dialog['overscroll-behavior']).toBe('contain')
    expect(source('SettingsDialog.vue')).toContain('ym-entry-dialog ym-settings-dialog')
  })

  it('locks background document scrolling only while a game modal is present and preserves its gutter', () => {
    const page = declarations(playability, 'html:has(.ym-app)')
    expect(page['scrollbar-gutter']).toBe('stable')
    expect(page.overflow).toBeUndefined()
    expect(page['overflow-y']).toBeUndefined()
    expect(declarations(playability, 'html:has(.ym-app .ym-overlay)').overflow).toBe('hidden')
  })

  it('gives fan hints the full phone sidebar row without changing the reserved card height', () => {
    const phoneStyles = study.slice(study.indexOf('@media (max-width: 640px)'))
    expect(declarations(phoneStyles, '.ym-app .ym-sidebar > .ym-hints')['grid-column']).toBe('1 / -1')
    const card = declarations(study, '.ym-app .ym-hints')
    expect(card.height).toBe('184px')
    expect(card['min-height']).toBe(card.height)
    expect(card['max-height']).toBe(card.height)
    expect(declarations(study, '.ym-app .ym-hint-tile').flex).toBe('0 0 50px')
    const content = declarations(study, '.ym-app .ym-hints-content')
    expect(content['overflow-x']).toBe('hidden')
    expect(content['overflow-y']).toBe('auto')
  })

  it('keeps the settlement shell fixed, the detail body vertical and the winning hand horizontal', () => {
    expect(declarations(settlement, '.ym-app .ym-result-dialog.ym-result-explained').overflow).toBe('hidden')
    const content = declarations(settlement, '.ym-app .ym-result-explained .ym-result-content')
    expect(content['overflow-x']).toBe('hidden')
    expect(content['overflow-y']).toBe('auto')
    const hand = declarations(settlement, '.ym-app .ym-result-explained .ym-winning-hand')
    expect(hand['overflow-x']).toBe('auto')
    expect(hand['overflow-y']).toBe('hidden')
    expect(hand['scrollbar-width']).toBe('thin')
  })

  it('allows long room names to wrap without forcing a page-wide horizontal scroll', () => {
    expect(declarations(playability, '.ym-app .ym-room-heading > div')['min-width']).toBe('0')
    expect(declarations(playability, '.ym-app .ym-room-heading h1')['overflow-wrap']).toBe('anywhere')
  })
})

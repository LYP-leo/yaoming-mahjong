import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineComponent, h } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import MahjongTileFace from './components/MahjongTileFace.vue'
import TileView from './yaoming/TileView.vue'
import { TILE_FACES, tileAriaLabel } from './tileArt'
import { TILE_ARTWORK_IMAGE, TILE_ARTWORK_SIZE, tileArtwork } from './tileArtwork'

interface Artwork { file: string; x: number; y: number; width: number; height: number }
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })
function face(suit: string, rank: number, extra: { red?: boolean; label?: string } = {}) {
  const wrapper = mount(MahjongTileFace, { props: { suit, rank, ...extra } })
  wrappers.push(wrapper); return wrapper
}
const sourceFiles = [
  ...['m', 'p', 's'].flatMap(suit => Array.from({ length: 9 }, (_, index) => `${index + 1}${suit}`)),
  ...Array.from({ length: 7 }, (_, index) => `${index + 1}z`),
  '0m', '0p', '0s',
]
const expectedCells: Record<string, Artwork> = Object.fromEntries(sourceFiles.map((file, index) => [file, {
  file, x: (index % 9) * 19, y: Math.floor(index / 9) * 26, width: 19, height: 26,
}]))
function expectedFile(suit: string, rank: number, red = false) {
  if (suit === 'HONORS') return `${[1, 2, 3, 4, 7, 6, 5][rank - 1]}z`
  const suffix = ({ CHARACTERS: 'm', DOTS: 'p', BAMBOO: 's' } as Record<string, string>)[suit]
  return `${red && rank === 5 ? 0 : rank}${suffix}`
}
function expectedArtwork(suit: string, rank: number, red = false) { return expectedCells[expectedFile(suit, rank, red)] }
function expectedTransform(artwork: Artwork) {
  const scale = Math.min(60 / artwork.width, 84 / artwork.height)
  return `translate(${(60 - artwork.width * scale) / 2} ${(84 - artwork.height * scale) / 2}) scale(${scale}) translate(${-artwork.x} ${-artwork.y})`
}
function expectExactImage(wrapper: VueWrapper, artwork: Artwork) {
  expect(wrapper.attributes('data-source-tile')).toBe(artwork.file)
  expect(wrapper.attributes('data-reference-crop')).toBeUndefined()
  expect(wrapper.findAll('image')).toHaveLength(1)
  const image = wrapper.get('image.mahjong-upstream-image')
  expect(image.attributes('href')).toBe(TILE_ARTWORK_IMAGE)
  expect(image.attributes('width')).toBe('171')
  expect(image.attributes('height')).toBe('130')
  expect(image.attributes('transform')).toBe(expectedTransform(artwork))
  expect(image.attributes('filter')).toBeUndefined()
  expect(wrapper.findAll('filter,.tile-reference-red')).toHaveLength(0)
  expect(wrapper.findAll('clipPath')).toHaveLength(1)
  const clipping = wrapper.get('clipPath'), rect = clipping.get('rect')
  const scale = Math.min(60 / artwork.width, 84 / artwork.height)
  expect(Number(rect.attributes('x'))).toBeCloseTo((60 - artwork.width * scale) / 2)
  expect(Number(rect.attributes('y'))).toBeCloseTo((84 - artwork.height * scale) / 2)
  expect(Number(rect.attributes('width'))).toBeCloseTo(artwork.width * scale)
  expect(Number(rect.attributes('height'))).toBeCloseTo(artwork.height * scale)
  expect(image.element.parentElement?.getAttribute('clip-path')).toBe(`url(#${clipping.attributes('id')})`)
  expect(image.element.parentElement?.getAttribute('aria-hidden')).toBe('true')
}

describe('local upstream SVG artwork mapping', () => {
  it('uses a local 171 by 130 SVG atlas without a screenshot, remote request or runtime font', () => {
    expect(TILE_ARTWORK_SIZE).toEqual({ width: 171, height: 130 })
    expect(TILE_ARTWORK_IMAGE).toMatch(/atlas\.svg(?:\?.*)?$/)
    expect(TILE_ARTWORK_IMAGE).not.toMatch(/^(?:https?:|data:|blob:)/)
    expect(TILE_ARTWORK_IMAGE).not.toMatch(/\.(?:png|webp|jpe?g)(?:\?|$)/)
    const renderer = readFileSync(resolve(process.cwd(), 'src/components/MahjongTileFace.vue'), 'utf8')
    expect(renderer).toContain("from '../tileArtwork'")
    expect(renderer).not.toMatch(/(?:MahjongGlyph|dotLayout|bambooLayout|<text\b|<foreignObject\b|<filter\b|font-family|https?:\/\/|data:image\/)/)
    const sourceRoot = resolve(process.cwd(), 'src')
    for (const entry of readdirSync(sourceRoot, { recursive: true })) {
      if (typeof entry !== 'string' || !/\.(?:vue|ts|css)$/.test(entry) || /\.test\.ts$/.test(entry)) continue
      const source = readFileSync(resolve(sourceRoot, entry), 'utf8')
      expect(source, entry).not.toMatch(/mahjong-reference-atlas\.png|mahjong-cropped-reference-20260908\.png|mahjong-tile-atlas\.(?:webp|png)/)
    }
  })

  it('retains all 34 public tile values and 37 distinct upstream source cells including native red fives', () => {
    expect(TILE_FACES).toHaveLength(34)
    expect(new Set(TILE_FACES.map(tile => `${tile.suit}-${tile.rank}`)).size).toBe(34)
    for (const suit of ['DOTS', 'BAMBOO', 'CHARACTERS', 'HONORS']) {
      expect(TILE_FACES.filter(tile => tile.suit === suit).map(tile => tile.rank).sort((a, b) => a - b))
        .toEqual(Array.from({ length: suit === 'HONORS' ? 7 : 9 }, (_, index) => index + 1))
    }
    const cells = [
      ...TILE_FACES.map(tile => tileArtwork(tile.suit, tile.rank)!),
      ...['CHARACTERS', 'DOTS', 'BAMBOO'].map(suit => tileArtwork(suit, 5, true)!),
    ]
    expect(cells.map(cell => cell.file).sort()).toEqual([...sourceFiles].sort())
    expect(new Set(cells.map(cell => `${cell.x},${cell.y}`)).size).toBe(37)
    for (const cell of cells) {
      expect(cell).toEqual(expectedCells[cell.file])
      expect(cell.x).toBeGreaterThanOrEqual(0); expect(cell.y).toBeGreaterThanOrEqual(0)
      expect(cell.x + cell.width).toBeLessThanOrEqual(TILE_ARTWORK_SIZE.width)
      expect(cell.y + cell.height).toBeLessThanOrEqual(TILE_ARTWORK_SIZE.height)
      for (const other of cells.filter(other => other.file !== cell.file)) {
        const overlap = cell.x < other.x + other.width && cell.x + cell.width > other.x
          && cell.y < other.y + other.height && cell.y + cell.height > other.y
        expect(overlap).toBe(false)
      }
    }
  })

  it.each(TILE_FACES)('selects the exact complete upstream SVG cell for $suit $rank', tile => {
    expect(tileArtwork(tile.suit, tile.rank)).toEqual(expectedArtwork(tile.suit, tile.rank))
  })

  it('keeps seven dots and seven bamboo distinct and uses their original upstream identifiers', () => {
    expect(tileArtwork('DOTS', 7)).toEqual({ file: '7p', x: 114, y: 26, width: 19, height: 26 })
    expect(tileArtwork('BAMBOO', 7)).toEqual({ file: '7s', x: 114, y: 52, width: 19, height: 26 })
  })

  it('maps game red/green/white dragons to upstream 7z/6z/5z instead of rank-matching the wrong artwork', () => {
    expect([1, 2, 3, 4, 5, 6, 7].map(rank => tileArtwork('HONORS', rank)?.file))
      .toEqual(['1z', '2z', '3z', '4z', '7z', '6z', '5z'])
    expect(tileAriaLabel('HONORS', 7)).toBe('白板')
  })

  it.each([0, 10, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY])('rejects invalid rank %s without borrowing a zero/red or adjacent source tile', rank => {
    for (const suit of ['DOTS', 'BAMBOO', 'CHARACTERS', 'HONORS']) {
      expect(tileArtwork(suit, rank)).toBeUndefined()
      expect(tileArtwork(suit, rank, true)).toBeUndefined()
    }
  })

  it('retains accessible red names and rejects unsupported ranks and suits', () => {
    expect(tileAriaLabel('DOTS', 5, true)).toBe('红宝牌 5筒')
    for (const [suit, rank] of [['DOTS', 0], ['DOTS', 10], ['BAMBOO', 1.5], ['HONORS', 8], ['FLOWERS', 1], ['', 1]] as const) {
      expect(tileArtwork(suit, rank)).toBeUndefined()
      expect(tileArtwork(suit, rank, true)).toBeUndefined()
      expect(tileAriaLabel(suit, rank)).toBe('未知麻将牌')
    }
  })
})

describe('shared upstream SVG renderer', () => {
  it.each(TILE_FACES)('renders $suit $rank with one accessible original SVG cell and no visible labels or tinting', tile => {
    const wrapper = face(tile.suit, tile.rank)
    expect(wrapper.findAll('svg')).toHaveLength(1)
    expect(wrapper.attributes('viewBox')).toBe('0 0 60 84')
    expect(wrapper.attributes('data-face')).toBe(`${tile.suit}-${tile.rank}`)
    expect(wrapper.attributes('data-artwork')).toBe('mahjong-graphic')
    expect(wrapper.attributes('preserveAspectRatio')).toBe('xMidYMid meet')
    expect(wrapper.attributes('role')).toBe('img')
    expect(wrapper.attributes('aria-label')).toBe(tileAriaLabel(tile.suit, tile.rank))
    expect(wrapper.find('title').text()).toBe(tileAriaLabel(tile.suit, tile.rank))
    expect(wrapper.findAll('text,foreignObject,script,.aka-mark,[data-red-style="badge"],filter')).toHaveLength(0)
    expectExactImage(wrapper, expectedArtwork(tile.suit, tile.rank))
    const visible = wrapper.element.cloneNode(true) as Element
    visible.querySelectorAll('title,desc').forEach(node => node.remove())
    expect(visible.textContent?.trim()).toBe('')
    for (const resource of wrapper.findAll('[href],[xlink\\:href]'))
      expect(resource.attributes('href') || resource.attributes('xlink:href')).toBe(TILE_ARTWORK_IMAGE)
  })

  it.each([['DOTS', 1], ['DOTS', 7], ['BAMBOO', 1], ['BAMBOO', 7], ['BAMBOO', 8]] as const)('uses upstream special %s %i artwork without synthesizing rings, birds or sticks', (suit, rank) => {
    const wrapper = face(suit, rank)
    expectExactImage(wrapper, expectedArtwork(suit, rank))
    expect(wrapper.findAll('.tile-dot,.tile-bamboo,.tile-bird,.tile-bamboo-eight,.tile-flower-petal,.tile-glyphs')).toHaveLength(0)
    expect(wrapper.find('.mahjong-unknown-face').exists()).toBe(false)
  })

  it('uses upstream 5z for the white dragon without inserting the previous hand-drawn frame', () => {
    const wrapper = face('HONORS', 7)
    expectExactImage(wrapper, expectedCells['5z'])
    expect(wrapper.findAll('.tile-honor-glyph,.tile-glyphs,path')).toHaveLength(0)
    expect(wrapper.attributes('aria-label')).toBe('白板')
  })

  it.each(['DOTS', 'BAMBOO', 'CHARACTERS'])('uses the native red %s five with unchanged tile identity, geometry and no filter', suit => {
    const standard = face(suit, 5), red = face(suit, 5, { red: true, label: '五牌' })
    expect(red.attributes('aria-label')).toBe('红宝牌 五牌')
    expect(red.attributes('data-face')).toBe(standard.attributes('data-face'))
    expectExactImage(standard, expectedArtwork(suit, 5))
    expectExactImage(red, expectedArtwork(suit, 5, true))
    expect(red.attributes('data-source-tile')).not.toBe(standard.attributes('data-source-tile'))
    expect(red.get('image').attributes('transform')).not.toBe(standard.get('image').attributes('transform'))
    expect(red.get('clipPath rect').attributes()).toEqual(standard.get('clipPath rect').attributes())
    expect(red.findAll('text,foreignObject,.aka-mark,[data-red-style="badge"],filter,.tile-reference-red')).toHaveLength(0)
  })

  it.each(TILE_FACES.filter(tile => tile.suit === 'HONORS' || tile.rank !== 5))('does not borrow a red five or recolor $suit $rank when given an unsupported red flag', tile => {
    expect(tileArtwork(tile.suit, tile.rank, true)).toEqual(tileArtwork(tile.suit, tile.rank))
    const wrapper = face(tile.suit, tile.rank, { red: true })
    expectExactImage(wrapper, expectedArtwork(tile.suit, tile.rank))
  })

  it('retains one accessible red prefix and custom names without displaying corner labels', () => {
    const wrapper = face('DOTS', 5, { red: true, label: '红宝牌 五筒' })
    expect(wrapper.attributes('aria-label')).toBe('红宝牌 五筒')
    expect(wrapper.find('title').text()).toBe('红宝牌 五筒')
    expect(wrapper.findAll('text')).toHaveLength(0)
  })

  it('gives all 37 simultaneous ordinary and red faces independent local clip IDs without masking another tile', () => {
    const examples = [
      ...TILE_FACES.map(tile => ({ suit: tile.suit, rank: tile.rank, red: false })),
      ...['DOTS', 'BAMBOO', 'CHARACTERS'].map(suit => ({ suit, rank: 5, red: true })),
    ]
    const wrapper = mount(defineComponent({ setup: () => () => h('div', examples.map(props => h(MahjongTileFace, props))) }))
    wrappers.push(wrapper)
    expect(wrapper.findAll('svg').map(svg => svg.attributes('data-face'))).toEqual(examples.map(tile => `${tile.suit}-${tile.rank}`))
    const ids = wrapper.findAll('clipPath').map(definition => definition.attributes('id'))
    expect(ids).toHaveLength(37); expect(new Set(ids).size).toBe(37)
    expect(wrapper.findAll('filter')).toHaveLength(0)
    for (const svg of wrapper.findAll('svg')) {
      const localId = svg.get('clipPath').attributes('id')
      for (const reference of svg.findAll('[clip-path]')) expect(reference.attributes('clip-path')).toBe(`url(#${localId})`)
      expect(svg.findAll('image')).toHaveLength(1)
      expect(svg.get('image').attributes('href')).toBe(TILE_ARTWORK_IMAGE)
    }
  })

  it('keeps a private back free of image requests, face, rank, ID and secret label', () => {
    const wrapper = mount(TileView, { props: { back: true, tile: { id: 'private', suit: 'DOTS', rank: 7, label: '秘密七筒' } } })
    wrappers.push(wrapper)
    expect(wrapper.findAll('svg,image,[href],[data-face],[data-source-tile]')).toHaveLength(0)
    expect(wrapper.html()).not.toContain('秘密七筒'); expect(wrapper.html()).not.toContain('private')
    expect(wrapper.attributes('title')).toBe('暗牌')
    expect(wrapper.classes()).not.toContain('ym-vector-tile')
    expect(wrapper.find('[aria-label="对手暗牌"]').exists()).toBe(true)
  })

  it('preserves small, selected and existing wrapper styles independently of the artwork source', () => {
    const wrapper = mount(TileView, { props: { small: true, selected: true, tile: { id: 'seven', suit: 'DOTS', rank: 7, label: '七筒' } } })
    wrappers.push(wrapper)
    expect(wrapper.classes()).toEqual(expect.arrayContaining(['ym-tile-small', 'ym-tile-selected', 'ym-vector-tile']))
    expect(wrapper.classes()).not.toContain('ym-reference-tile')
    expect(wrapper.find('svg').attributes('viewBox')).toBe('0 0 60 84')
    expect(wrapper.find('svg').attributes('data-source-tile')).toBe('7p')
    expect(wrapper.attributes('title')).toBe('七筒')
  })

  it.each([['FLOWERS', 20], ['DOTS', 0], ['HONORS', 8], ['BAMBOO', 1.5]] as const)('renders invalid %s %s as unknown without loading or revealing any source cell', (suit, rank) => {
    const wrapper = face(suit, rank)
    expect(wrapper.find('.mahjong-unknown-face').exists()).toBe(true)
    expect(wrapper.attributes('aria-label')).toBe('未知麻将牌')
    expect(wrapper.attributes('data-source-tile')).toBeUndefined()
    expect(wrapper.findAll('image,text,filter,clipPath,.tile-reference-red')).toHaveLength(0)
  })
})

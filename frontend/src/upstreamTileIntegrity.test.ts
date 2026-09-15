import { createHash } from 'node:crypto'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { buildAtlas, namespaceSourceSvg, PINNED_COMMIT, runPipeline, SOURCE_MANIFEST_SHA256, TILE_CODES, validateSourceSvg } from '../scripts/build-upstream-tiles.mjs'
import sourceManifest from '../artwork/mahjong-graphic/manifest.json'
import atlasMetadata from './assets/mahjong-graphic-atlas.json'

const artworkRoot = resolve('artwork/mahjong-graphic')
const sha256 = (bytes: Buffer | string) => createHash('sha256').update(bytes).digest('hex')
const sources = Object.fromEntries(TILE_CODES.map(code => [code, readFileSync(resolve(artworkRoot, `tiles/${code}.svg`), 'utf8')]))
const atlas = readFileSync(resolve('src/assets/mahjong-graphic-atlas.svg'), 'utf8')
const parse = (source: string) => new DOMParser().parseFromString(source, 'image/svg+xml')
const atlasDocument = parse(atlas)
const manifestFiles = sourceManifest.files as Record<string, { bytes: number; sha256: string }>
const expectedOrder = ['m', 'p', 's'].flatMap(suit => Array.from({ length: 9 }, (_, index) => `${index + 1}${suit}`))
  .concat(Array.from({ length: 7 }, (_, index) => `${index + 1}z`), ['0m', '0p', '0s'])

describe('pinned mahjong_graphic original assets and public attribution', () => {
  it('locks the selected source manifest, upstream commit and the exact 37-file set', () => {
    expect(PINNED_COMMIT).toBe('3e275804ff58325306710bef3a7406860444bc6a')
    expect(sourceManifest.upstream).toEqual({ repository: 'https://github.com/lietxia/mahjong_graphic', commit: PINNED_COMMIT, directory: 'Vectors 矢量图/SVG' })
    expect(SOURCE_MANIFEST_SHA256).toBe('7a03d4840d53b4113324263598a9acf55da39346a03f172ff5cd351d7be41834')
    expect(sha256(readFileSync(resolve(artworkRoot, 'manifest.json')))).toBe(SOURCE_MANIFEST_SHA256)
    expect(TILE_CODES).toEqual(expectedOrder)
    expect(readdirSync(resolve(artworkRoot, 'tiles')).sort()).toEqual(expectedOrder.map(code => `${code}.svg`).sort())
    expect(Object.keys(sourceManifest.files)).toEqual(expectedOrder.map(code => `${code}.svg`))
  })

  it('ships the verbatim upstream license and provenance without requiring the vendor checkout at build time', () => {
    const license = readFileSync(resolve(artworkRoot, 'LICENSE'))
    expect(sha256(license)).toBe(sourceManifest.license.sha256)
    expect(license.toString()).toContain('Unlimited permission is granted to use, copy, and distribute')
    const provenance = readFileSync(resolve(artworkRoot, 'PROVENANCE.md'), 'utf8')
    expect(provenance).toContain(PINNED_COMMIT)
    expect(provenance).toContain('https://github.com/lietxia/mahjong_graphic')
    expect(provenance).toContain('https://github.com/SyaoranHinata/I.Mahjong')
    expect(provenance).toContain('GL-MahjongTile')
    for (const name of ['LICENSE', 'PROVENANCE.md', 'manifest.json'])
      expect(readFileSync(resolve('public/third-party/mahjong-graphic', name)).equals(readFileSync(resolve(artworkRoot, name)))).toBe(true)
    const builder = readFileSync(resolve('scripts/build-upstream-tiles.mjs'), 'utf8')
    expect(builder).not.toMatch(/(?:vendor\/|from ['"]sharp|fetch\(|https?\.get\()/)
  })

  it.each(TILE_CODES)('preserves %s original bytes, all shape geometry and fill colors while isolating styles', code => {
    const original = readFileSync(resolve(artworkRoot, `tiles/${code}.svg`))
    expect({ bytes: original.length, sha256: sha256(original) }).toEqual(manifestFiles[`${code}.svg`])
    const validated = validateSourceSvg(sources[code], code)
    const document = parse(sources[code]), cell = atlasDocument.querySelector(`[data-upstream-tile="${code}"]`)!
    expect(cell).not.toBeNull()
    expect(cell.getAttribute('viewBox')).toBe('0 0 19 26')
    expect(cell.getAttribute('width')).toBe('19'); expect(cell.getAttribute('height')).toBe('26')
    expect(cell.getAttribute('id')).toBe(`mg-${code}-${document.documentElement.getAttribute('id')}`)
    const originalShapes = [...document.querySelectorAll('path,rect,circle')], generatedShapes = [...cell.querySelectorAll('path,rect,circle')]
    expect(generatedShapes).toHaveLength(originalShapes.length)
    for (const [index, originalShape] of originalShapes.entries()) {
      const generatedShape = generatedShapes[index]
      expect(generatedShape.tagName).toBe(originalShape.tagName)
      const expectedAttributes = Object.fromEntries([...originalShape.attributes].map(attribute =>
        [attribute.name, attribute.name === 'class' ? `mg-${code}-${attribute.value}` : attribute.value]))
      expect(Object.fromEntries([...generatedShape.attributes].map(attribute => [attribute.name, attribute.value]))).toEqual(expectedAttributes)
    }
    const style = cell.querySelector('style')!.textContent!
    const fillRules = [...style.matchAll(/\.(mg-[\w-]+)\s*\{\s*fill:\s*(#[\da-fA-F]{6});\s*\}/g)]
    expect(Object.fromEntries(fillRules.map(match => [match[1], match[2]])))
      .toEqual(Object.fromEntries(Object.entries(validated.classes).map(([name, value]) => [`mg-${code}-${name}`, value])))
    expect([...cell.querySelectorAll('[class]')].every(element => element.getAttribute('class')!.startsWith(`mg-${code}-`))).toBe(true)
  })
})

describe('deterministic vector atlas and isolated cells', () => {
  it('reproduces the checked-in SVG and coordinate JSON exactly from local originals', () => {
    const built = buildAtlas(sources)
    expect(built.svg).toBe(atlas)
    expect(built.metadata).toEqual(atlasMetadata)
    expect(readFileSync(resolve('src/assets/mahjong-graphic-atlas.json'), 'utf8')).toBe(JSON.stringify(built.metadata, null, 2) + '\n')
    expect(Object.keys(built.metadata.tiles)).toEqual(expectedOrder)
    expect(built.metadata.width).toBe(171); expect(built.metadata.height).toBe(130)
    for (const [index, code] of expectedOrder.entries()) {
      expect(built.metadata.tiles[code]).toEqual({ x: index % 9 * 19, y: Math.floor(index / 9) * 26, width: 19, height: 26 })
      const cell = atlasDocument.querySelector(`[data-upstream-tile="${code}"]`)!
      expect(Number(cell.getAttribute('x'))).toBe(built.metadata.tiles[code].x)
      expect(Number(cell.getAttribute('y'))).toBe(built.metadata.tiles[code].y)
    }
  })

  it('has no embedded raster, fonts, active nodes, external links or shared class/ID collisions', () => {
    expect(atlasDocument.querySelector('parsererror')).toBeNull()
    expect(atlasDocument.documentElement.getAttribute('viewBox')).toBe('0 0 171 130')
    expect(atlasDocument.querySelectorAll('[data-upstream-tile]')).toHaveLength(37)
    expect(atlasDocument.querySelectorAll('script,image,img,text,foreignObject,use,a,animate,animateTransform,set,font,iframe,[href]')).toHaveLength(0)
    expect(atlas).not.toMatch(/(?:url\s*\(|@import|@font-face|font-family|data:image|onload\s*=|onclick\s*=|\.cls-\d+)/i)
    const ids = [...atlasDocument.querySelectorAll('[id]')].map(element => element.id)
    expect(ids).toHaveLength(37); expect(new Set(ids).size).toBe(ids.length)
    const selectors = [...atlas.matchAll(/\.(mg-[\w-]+)\s*\{/g)].map(match => match[1])
    expect(new Set(selectors).size).toBe(selectors.length)
    expect([...atlasDocument.querySelectorAll('[class]')].every(element => selectors.includes(element.getAttribute('class')!))).toBe(true)
  })

  it('includes upstream native red fives as independent source geometry, without recoloring filters', () => {
    for (const suit of ['m', 'p', 's']) {
      const redCode = `0${suit}`, normalCode = `5${suit}`
      expect(manifestFiles[`${redCode}.svg`].sha256).not.toBe(manifestFiles[`${normalCode}.svg`].sha256)
      expect(sources[redCode]).toContain('#ca2a20')
      expect(atlasDocument.querySelector(`[data-upstream-tile="${redCode}"] filter`)).toBeNull()
      expect(atlasDocument.querySelector(`[data-upstream-tile="${redCode}"]`)?.getAttribute('viewBox')).toBe('0 0 19 26')
    }
  })

  it('rejects incomplete source sets and invalid target cells', () => {
    const missing = { ...sources }; delete missing['7z']
    expect(() => buildAtlas(missing)).toThrow('exactly 37')
    expect(() => buildAtlas({ ...sources, '8z': sources['7z'] })).toThrow('exactly 37')
    expect(() => namespaceSourceSvg(sources['1m'], '../bad', 0, 0)).toThrow('Invalid atlas cell')
    expect(() => namespaceSourceSvg(sources['1m'], '1m', -1, 0)).toThrow('Invalid atlas cell')
    expect(() => namespaceSourceSvg(sources['1m'], '1m', 0, 0.5)).toThrow('Invalid atlas cell')
  })

  it('runs --check logic without modifying source, generated resources or attribution', async () => {
    const files = ['src/assets/mahjong-graphic-atlas.svg', 'src/assets/mahjong-graphic-atlas.json',
      ...['LICENSE', 'PROVENANCE.md', 'manifest.json'].map(name => `public/third-party/mahjong-graphic/${name}`)]
    const times = files.map(file => statSync(resolve(file)).mtimeMs)
    expect(await runPipeline(true)).toMatchObject({ mode: 'check', commit: PINNED_COMMIT, tiles: 37, width: 171, height: 130, atlasSha256: sha256(atlas) })
    expect(files.map(file => statSync(resolve(file)).mtimeMs)).toEqual(times)
  })
})

describe('source SVG safety validator fails closed', () => {
  const add = (markup: string) => sources['1m'].replace('</svg>', `${markup}</svg>`)
  it.each([
    ['script', add('<script>alert(1)</script>')],
    ['raster', add('<image href="data:image/png;base64,AAAA"/>')],
    ['external image', add('<image href="https://example.test/tile.png"/>')],
    ['text', add('<text>1</text>')],
    ['foreignObject', add('<foreignObject/>')],
    ['use', add('<use href="#other"/>')],
    ['animation', add('<animate attributeName="x"/>')],
    ['nested SVG', add('<svg viewBox="0 0 19 26"/>')],
    ['event handler', sources['1m'].replace('<svg ', '<svg onload="alert(1)" ')],
    ['inline style', sources['1m'].replace('<path ', '<path style="fill:red" ')],
    ['font rule', sources['1m'].replace('<style>', '<style>@font-face { font-family: x; src: url(x); }')],
    ['import rule', sources['1m'].replace('<style>', '<style>@import "https://example.test/x.css";')],
    ['fill URL', sources['1m'].replace('fill: #93989c;', 'fill: url(https://example.test/x);')],
    ['compound selector', sources['1m'].replace('.cls-1 {', 'svg .cls-1 {')],
    ['duplicate rule', sources['1m'].replace('<style>', '<style>.cls-1 { fill: #ffffff; }')],
    ['doctype', '<!DOCTYPE svg SYSTEM "https://example.test/a.dtd">' + sources['1m']],
    ['entity', add('<g>&#65;</g>')],
    ['processing instruction', add('<?xml-stylesheet href="https://example.test/a.css"?>')],
    ['foreign namespace', add('<g xmlns="https://example.test/other"/>')],
    ['malformed XML', sources['1m'].replace('</svg>', '')],
    ['wrong geometry', sources['1m'].replace('viewBox="0 0 19 26"', 'viewBox="0 0 20 26"')],
  ])('rejects %s', (_, svg) => {
    expect(() => validateSourceSvg(svg)).toThrow()
  })
})

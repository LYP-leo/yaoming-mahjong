import { createHash } from 'node:crypto'
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'

export const PINNED_COMMIT = '3e275804ff58325306710bef3a7406860444bc6a'
export const SOURCE_MANIFEST_SHA256 = '7a03d4840d53b4113324263598a9acf55da39346a03f172ff5cd351d7be41834'
export const TILE_CODES = Object.freeze([
  ...['m', 'p', 's'].flatMap(suit => Array.from({ length: 9 }, (_, index) => `${index + 1}${suit}`)),
  ...Array.from({ length: 7 }, (_, index) => `${index + 1}z`), '0m', '0p', '0s',
])
const namespace = 'http://www.w3.org/2000/svg'
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const sourceRoot = resolve(root, 'artwork/mahjong-graphic')
const numeric = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/
const attributes = {
  svg: new Set(['id', 'data-name', 'xmlns', 'viewBox']),
  defs: new Set(), style: new Set(), g: new Set(),
  rect: new Set(['class', 'x', 'y', 'width', 'height', 'rx', 'ry']),
  path: new Set(['class', 'd']), circle: new Set(['class', 'cx', 'cy', 'r']),
}

/** Fail closed: accept only the small, path-based SVG vocabulary in the pinned files.
 * No DOM is inserted in the app and JSDOM's script/resource execution is not enabled.
 */
export function validateSourceSvg(source, label = 'source') {
  const fail = message => { throw new Error(`${label}: ${message}`) }
  if (typeof source !== 'string' || source.length > 250_000) fail('invalid source size')
  const body = source.replace(/^\uFEFF?<\?xml\s+version="1\.0"\s+encoding="UTF-8"\?>\s*/, '')
  if (/<[!?]|&/.test(body)) fail('declarations, processing instructions and entities are forbidden')
  let dom
  try { dom = new JSDOM(body, { contentType: 'image/svg+xml' }) }
  catch { fail('malformed SVG XML') }
  try {
    const document = dom.window.document, svg = document.documentElement
    if (svg.tagName !== 'svg' || svg.namespaceURI !== namespace || svg.getAttribute('viewBox') !== '0 0 19 26') fail('unexpected root or viewBox')
    const elements = [...document.querySelectorAll('*')], fills = new Map()
    const styles = elements.filter(element => element.tagName === 'style')
    if (styles.length !== 1 || styles[0].parentElement?.tagName !== 'defs' || styles[0].parentElement?.parentElement !== svg) fail('expected one local style block')
    let remaining = styles[0].textContent.trim()
    while (remaining) {
      const match = /^\.(cls-\d+)\s*\{\s*fill:\s*(#[0-9a-fA-F]{6});\s*\}\s*/.exec(remaining)
      if (!match || fills.has(match[1])) fail('only unique simple class fill rules are permitted')
      fills.set(match[1], match[2]); remaining = remaining.slice(match[0].length)
    }
    if (!fills.size) fail('missing fill rules')
    for (const element of elements) {
      const allowed = attributes[element.tagName]
      if (!allowed || element.namespaceURI !== namespace || (element.tagName === 'svg' && element !== svg)) fail(`forbidden element ${element.tagName}`)
      for (const attribute of element.attributes) {
        if (!allowed.has(attribute.name)) fail(`forbidden attribute ${attribute.name}`)
        if (attribute.name === 'class' && !fills.has(attribute.value)) fail('undefined or compound class')
        if (attribute.name === 'd' && (!attribute.value || !/^[MmZzLlHhVvCcSsQqTtAa0-9eE+.,\s-]+$/.test(attribute.value))) fail('invalid path data')
        if (['x', 'y', 'width', 'height', 'rx', 'ry', 'cx', 'cy', 'r'].includes(attribute.name)
          && (!numeric.test(attribute.value) || !Number.isFinite(Number(attribute.value)))) fail('invalid numeric geometry')
      }
      if (element !== svg && element.hasAttribute('id')) fail('unexpected non-root ID')
      if (['path', 'rect', 'circle'].includes(element.tagName) && (!element.hasAttribute('class') || element.children.length)) fail('invalid shape')
      if (element.tagName === 'path' && !element.hasAttribute('d')) fail('missing path data')
      if (element.tagName === 'style' && element.children.length) fail('markup in CSS is forbidden')
      if (element.tagName !== 'style' && [...element.childNodes].some(node => node.nodeType === 3 && node.textContent.trim())) fail('visible text is forbidden')
    }
    if (svg.getAttribute('id') !== '_图层_1' || svg.getAttribute('data-name') !== '图层 1') fail('unexpected source metadata')
    if (!svg.querySelector('path')) fail('missing original path geometry')
    return { classes: Object.fromEntries(fills), pathCount: svg.querySelectorAll('path').length }
  } finally { dom.window.close() }
}

/** Namespace identifiers without serializing or changing any path/shape/color value. */
export function namespaceSourceSvg(source, code, x, y) {
  if (!TILE_CODES.includes(code) || !Number.isInteger(x) || !Number.isInteger(y) || x < 0 || y < 0) throw new Error('Invalid atlas cell')
  validateSourceSvg(source, code)
  const prefix = `mg-${code}-`
  return source.replace(/^\uFEFF?<\?xml\s+version="1\.0"\s+encoding="UTF-8"\?>\s*/, '').trim()
    .replace(/\bclass="(cls-\d+)"/g, (_, name) => `class="${prefix}${name}"`)
    .replace(/\bid="([^"]+)"/g, (_, id) => `id="${prefix}${id}"`)
    .replace(/\.(cls-\d+)/g, (_, name) => `.${prefix}${name}`)
    .replace('<svg ', `<svg x="${x}" y="${y}" width="19" height="26" data-upstream-tile="${code}" `)
}

export function buildAtlas(sources) {
  if (JSON.stringify(Object.keys(sources).sort()) !== JSON.stringify([...TILE_CODES].sort())) throw new Error('Expected exactly 37 upstream tiles')
  const metadata = { width: 171, height: 130, tiles: {} }
  const cells = TILE_CODES.map((code, index) => {
    const x = index % 9 * 19, y = Math.floor(index / 9) * 26
    metadata.tiles[code] = { x, y, width: 19, height: 26 }
    return namespaceSourceSvg(sources[code], code, x, y)
  })
  const svg = `<?xml version="1.0" encoding="UTF-8"?>\n<svg xmlns="${namespace}" width="171" height="130" viewBox="0 0 171 130">\n${cells.join('\n')}\n</svg>\n`
  return { svg, metadata }
}

export async function runPipeline(check = false) {
  const manifestBytes = await readFile(resolve(sourceRoot, 'manifest.json'))
  if (sha256(manifestBytes) !== SOURCE_MANIFEST_SHA256) throw new Error('Pinned source manifest changed')
  const manifest = JSON.parse(manifestBytes.toString('utf8'))
  if (manifest.upstream.commit !== PINNED_COMMIT || manifest.upstream.repository !== 'https://github.com/lietxia/mahjong_graphic'
    || manifest.upstream.directory !== 'Vectors 矢量图/SVG') throw new Error('Unexpected source provenance')
  const names = TILE_CODES.map(code => `${code}.svg`)
  const copiedNames = await readdir(resolve(sourceRoot, 'tiles'))
  if (JSON.stringify(copiedNames.sort()) !== JSON.stringify([...names].sort())
    || JSON.stringify(Object.keys(manifest.files).sort()) !== JSON.stringify([...names].sort())) throw new Error('Pinned source set changed')
  const license = await readFile(resolve(sourceRoot, 'LICENSE'))
  if (sha256(license) !== manifest.license.sha256) throw new Error('Upstream license changed')
  const sources = {}
  for (const code of TILE_CODES) {
    const bytes = await readFile(resolve(sourceRoot, `tiles/${code}.svg`)), expected = manifest.files[`${code}.svg`]
    if (bytes.length !== expected.bytes || sha256(bytes) !== expected.sha256) throw new Error(`${code}: original SVG bytes changed`)
    sources[code] = bytes.toString('utf8')
  }
  const { svg, metadata } = buildAtlas(sources)
  const publicRoot = resolve(root, 'public/third-party/mahjong-graphic')
  const outputs = [
    [resolve(root, 'src/assets/mahjong-graphic-atlas.svg'), Buffer.from(svg)],
    [resolve(root, 'src/assets/mahjong-graphic-atlas.json'), Buffer.from(JSON.stringify(metadata, null, 2) + '\n')],
    [resolve(publicRoot, 'LICENSE'), license],
    [resolve(publicRoot, 'PROVENANCE.md'), await readFile(resolve(sourceRoot, 'PROVENANCE.md'))],
    [resolve(publicRoot, 'manifest.json'), manifestBytes],
  ]
  for (const [path, expected] of outputs) {
    if (check) {
      if (!(await readFile(path)).equals(expected)) throw new Error(`Generated file differs: ${path}`)
    } else {
      await mkdir(dirname(path), { recursive: true })
      await writeFile(path, expected)
    }
  }
  return { mode: check ? 'check' : 'build', commit: PINNED_COMMIT, tiles: TILE_CODES.length, width: metadata.width, height: metadata.height, atlasBytes: Buffer.byteLength(svg), atlasSha256: sha256(svg), sourceManifestSha256: sha256(manifestBytes) }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.slice(2).some(argument => argument !== '--check')) throw new Error('Usage: node scripts/build-upstream-tiles.mjs [--check]')
  console.log(JSON.stringify(await runPipeline(process.argv.includes('--check')), null, 2))
}

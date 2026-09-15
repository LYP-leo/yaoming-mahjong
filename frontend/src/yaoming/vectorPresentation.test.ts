import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('complete upstream SVG tile shells', () => {
  it('keeps the same artwork-only shadows, dimensions, selected outlines and back styles', () => {
    const source = readFileSync(resolve('src/yaoming/TileView.vue'), 'utf8')
    const shell = source.match(/\.ym-app \.ym-tile\.ym-vector-tile\{([^}]+)\}/)![1]
    expect(shell).toContain('box-shadow:none'); expect(shell).toContain('overflow:visible')
    expect(shell).not.toMatch(/(?:width|height|outline|transform):/)
    expect(source).toContain(':deep(.mahjong-art){filter:drop-shadow(0 2px 0')
    expect(source).toContain('.ym-meld-horizontal>.ym-tile.ym-vector-tile :deep(.mahjong-art){filter:drop-shadow(-2px 0 0')
    expect(source.slice(source.indexOf('<style'))).not.toContain('.ym-tile-back')
  })
})

import atlasImage from './assets/mahjong-graphic-atlas.svg'
import atlas from './assets/mahjong-graphic-atlas.json'

export const TILE_ARTWORK_IMAGE = atlasImage
export const TILE_ARTWORK_SIZE = Object.freeze({ width: atlas.width, height: atlas.height })
export interface TileArtwork {
  readonly file: string
  readonly x: number
  readonly y: number
  readonly width: number
  readonly height: number
}
const artworkByFile: Readonly<Record<string, TileArtwork>> = Object.freeze(Object.fromEntries(
  Object.entries(atlas.tiles).map(([file, rectangle]) => [file, Object.freeze({ file, ...rectangle })]),
))
// Upstream follows riichi's white/green/red order; the game uses red/green/white.
const honorFiles = ['1z', '2z', '3z', '4z', '7z', '6z', '5z'] as const
export function tileArtwork(suit: string, rank: number, red = false): TileArtwork | undefined {
  if (!Number.isInteger(rank) || rank < 1) return undefined
  if (suit === 'HONORS') return rank <= 7 ? artworkByFile[honorFiles[rank - 1]] : undefined
  if (rank > 9) return undefined
  const suffix = suit === 'CHARACTERS' ? 'm' : suit === 'DOTS' ? 'p' : suit === 'BAMBOO' ? 's' : undefined
  if (!suffix) return undefined
  return artworkByFile[`${red && rank === 5 ? 0 : rank}${suffix}`]
}

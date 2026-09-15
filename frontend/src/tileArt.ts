export type TileSuit = 'CHARACTERS' | 'BAMBOO' | 'DOTS' | 'HONORS'
export const TILE_FACES = Object.freeze((['CHARACTERS', 'BAMBOO', 'DOTS', 'HONORS'] as const)
  .flatMap(suit => Array.from({ length: suit === 'HONORS' ? 7 : 9 }, (_, index) => Object.freeze({ suit, rank: index + 1 }))))

export function isTileFace(suit: string, rank: number): boolean { return TILE_FACES.some(face => face.suit === suit && face.rank === rank) }
export function tileAriaLabel(suit: string, rank: number, red = false): string {
  if (!isTileFace(suit, rank)) return '未知麻将牌'
  const label = suit === 'HONORS'
    ? ['', '东风', '南风', '西风', '北风', '红中', '发财', '白板'][rank]
    : `${rank}${{ CHARACTERS: '万', BAMBOO: '索', DOTS: '筒' }[suit as Exclude<TileSuit, 'HONORS'>]}`
  return `${red ? '红宝牌 ' : ''}${label}`
}

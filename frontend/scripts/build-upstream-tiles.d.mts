export const PINNED_COMMIT: string
export const SOURCE_MANIFEST_SHA256: string
export const TILE_CODES: readonly string[]
export function validateSourceSvg(source: string, label?: string): { classes: Record<string, string>; pathCount: number }
export function namespaceSourceSvg(source: string, code: string, x: number, y: number): string
export function buildAtlas(sources: Record<string, string>): { svg: string; metadata: { width: number; height: number; tiles: Record<string, { x: number; y: number; width: number; height: number }> } }
export function runPipeline(check?: boolean): Promise<{ mode: string; commit: string; tiles: number; width: number; height: number; atlasBytes: number; atlasSha256: string; sourceManifestSha256: string }>

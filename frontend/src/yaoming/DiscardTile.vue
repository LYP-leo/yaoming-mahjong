<script setup lang="ts">
import { computed } from 'vue'
import TileView from './TileView.vue'
import { tileAriaLabel } from '../tileArt'
import type { DiscardKind, Tile } from './types'

const props = withDefaults(defineProps<{ tile: Tile; kind?: DiscardKind | null; small?: boolean; selected?: boolean }>(), { small: false, selected: false })
// The server records the physical entity that was discarded. Missing historical
// metadata (or a future enum value) is unknown, never inferred from tile shape.
const knownKind = computed(() => props.kind === 'TSUMOGIRI' || props.kind === 'TEDASHI' ? props.kind : undefined)
const accessibleName = computed(() => {
  let label = props.tile.label.trim() || tileAriaLabel(props.tile.suit, props.tile.rank)
  if (props.tile.red && !label.includes('红宝牌')) label = `红宝牌 ${label}`
  return label + (knownKind.value === 'TSUMOGIRI' ? ' · 摸切' : knownKind.value === 'TEDASHI' ? ' · 手切' : '')
})
</script>
<template>
  <TileView :tile="tile" :small="small" :selected="selected" class="ym-discard-tile" :class="{ 'ym-discard-tsumogiri': knownKind === 'TSUMOGIRI' }" :data-discard-kind="knownKind" :title="accessibleName" :aria-label="accessibleName" role="img" />
</template>
<style scoped>
/* Keep the same TileView root and box for every kind. The bar fits in the river's
   existing inter-row gap / bottom padding, so toggling it cannot move any tile. */
.ym-app .ym-discard-tile { position: relative; overflow: visible; }
.ym-app .ym-discard-tile.ym-discard-tsumogiri::after { content: ''; position: absolute; bottom: -4px; left: 32%; right: 32%; height: 2px; border-radius: 1px; background: #f4d487; pointer-events: none; }
</style>

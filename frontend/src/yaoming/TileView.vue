<script setup lang="ts">
import MahjongTileFace from '../components/MahjongTileFace.vue'
import type { Tile } from './types'
withDefaults(defineProps<{ tile?: Tile; back?: boolean; small?: boolean; selected?: boolean }>(), { back: false, small: false, selected: false })
</script>
<template>
  <span class="ym-tile" :class="{ 'ym-tile-back': back, 'ym-tile-small': small, 'ym-tile-selected': selected, 'ym-vector-tile': !back && !!tile }" :title="back ? '暗牌' : tile?.label">
    <span v-if="back" class="ym-tile-inlay" aria-label="对手暗牌" />
    <MahjongTileFace v-else-if="tile" :suit="tile.suit" :rank="tile.rank" :label="tile.label" :red="tile.red" />
  </span>
</template>

<style scoped>
/* The renderer contains the entire tile body. Shadow its opaque SVG silhouette, not
   the wrapper rectangle: aspect-ratio letterboxing must not leave a floating bar. */
.ym-app .ym-tile.ym-vector-tile{padding:0;border:0;background:transparent;box-shadow:none;overflow:visible}
.ym-app .ym-tile.ym-vector-tile :deep(.mahjong-art){filter:drop-shadow(0 2px 0 #a6b198) drop-shadow(0 3px 1px #09281f26)}
/* The parent rotates -90deg, so a local leftward shadow remains downward on the table. */
.ym-app .ym-meld-horizontal>.ym-tile.ym-vector-tile :deep(.mahjong-art){filter:drop-shadow(-2px 0 0 #a6b198) drop-shadow(-3px 0 1px #09281f26)}
</style>

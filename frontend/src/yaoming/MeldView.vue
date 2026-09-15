<script setup lang="ts">
import { computed } from 'vue'
import TileView from './TileView.vue'
import type { Meld } from './types'
const props = withDefaults(defineProps<{ meld: Meld; ownerSeat: number; reveal?: boolean; capacity?: number }>(), { reveal: false, capacity: 3 })
const addedTile = computed(() => props.meld.added ? props.meld.tiles.at(-1) : undefined)
const source = computed(() => {
  const count = props.capacity === 4 ? 4 : 3
  const distance = ((props.meld.fromSeat - props.ownerSeat) % count + count) % count
  return distance === 1 ? '下家' : count === 4 && distance === 2 ? '对家' : '上家'
})
const label = computed(() => props.meld.concealed ? '暗杠' : props.meld.added ? '加杠' : { CHI: '吃', PONG: '碰', KONG: '明杠' }[props.meld.type])
const laidTiles = computed(() => {
  const tiles = props.meld.tiles.filter(t => t.id !== addedTile.value?.id)
  if (props.meld.concealed) return tiles
  const claimed = tiles.find(t => t.id === props.meld.claimedTileId)
  if (!claimed) return tiles
  const others = tiles.filter(t => t.id !== claimed.id)
  if (props.meld.type === 'CHI' || source.value === '上家') return [claimed, ...others]
  if (source.value === '对家') { others.splice(Math.min(1, others.length), 0, claimed); return others }
  return [...others, claimed]
})
</script>
<template>
  <div class="ym-meld" :aria-label="`${label}${meld.concealed ? '' : ` · ${source}供牌`}`">
    <span v-for="(tile, index) in laidTiles" :key="tile.id" class="ym-meld-slot" :class="{ 'ym-meld-stack': addedTile && tile.id === meld.claimedTileId }" :data-tile-id="tile.id">
      <span v-if="addedTile && tile.id === meld.claimedTileId" class="ym-meld-horizontal ym-meld-added" aria-label="加杠叠牌"><TileView :tile="addedTile" small /></span>
      <span :class="{ 'ym-meld-horizontal': !meld.concealed && tile.id === meld.claimedTileId }" :aria-label="!meld.concealed && tile.id === meld.claimedTileId ? `${source}供牌` : undefined"><TileView :tile="tile" small :back="meld.concealed && !reveal && (index === 0 || index === laidTiles.length - 1)" /></span>
    </span>
    <span class="ym-meld-label">{{ label }}{{ meld.concealed ? '' : ` · ${source}` }}</span>
  </div>
</template>

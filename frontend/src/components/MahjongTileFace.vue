<script setup lang="ts">
import { computed, useId } from 'vue'
import { tileAriaLabel } from '../tileArt'
import { TILE_ARTWORK_IMAGE, TILE_ARTWORK_SIZE, tileArtwork } from '../tileArtwork'

const props = withDefaults(defineProps<{ suit: string; rank: number; red?: boolean; label?: string }>(), { red: false, label: '' })
const artwork = computed(() => tileArtwork(props.suit, props.rank, props.red))
const accessibleName = computed(() => {
  const label = props.label.trim() || tileAriaLabel(props.suit, props.rank)
  return props.red && !label.includes('红宝牌') ? `红宝牌 ${label}` : label
})
const clipId = `tile-artwork-${useId()}`
// Preserve upstream proportions within the existing hand/river/meld layout.
const scale = computed(() => artwork.value ? Math.min(60 / artwork.value.width, 84 / artwork.value.height) : 1)
const width = computed(() => artwork.value ? artwork.value.width * scale.value : 60)
const height = computed(() => artwork.value ? artwork.value.height * scale.value : 84)
const left = computed(() => (60 - width.value) / 2)
const top = computed(() => (84 - height.value) / 2)
const imageTransform = computed(() => artwork.value
  ? `translate(${left.value} ${top.value}) scale(${scale.value}) translate(${-artwork.value.x} ${-artwork.value.y})` : '')
</script>

<template>
  <svg class="mahjong-art" viewBox="0 0 60 84" role="img" :aria-label="accessibleName" :data-face="`${suit}-${rank}`" data-artwork="mahjong-graphic" :data-source-tile="artwork?.file" preserveAspectRatio="xMidYMid meet">
    <title>{{ accessibleName }}</title>
    <template v-if="artwork">
      <defs>
        <clipPath :id="clipId"><rect :x="left" :y="top" :width="width" :height="height" /></clipPath>
      </defs>
      <g :clip-path="`url(#${clipId})`" aria-hidden="true">
        <image class="mahjong-upstream-image" :href="TILE_ARTWORK_IMAGE" :width="TILE_ARTWORK_SIZE.width" :height="TILE_ARTWORK_SIZE.height" :transform="imageTransform" />
      </g>
    </template>
    <g v-else class="mahjong-unknown-face" aria-hidden="true">
      <rect x="2" y="1" width="56" height="82" rx="4" fill="#edf0e8" stroke="#a7b3a0" />
      <rect x="10" y="13" width="40" height="59" rx="3" fill="none" stroke="#a7b3a0" />
    </g>
  </svg>
</template>

<style scoped>
.mahjong-art{display:block;width:100%;height:100%;overflow:hidden;flex-shrink:0}
</style>

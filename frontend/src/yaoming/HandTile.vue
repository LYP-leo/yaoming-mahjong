<script setup lang="ts">
import TileView from './TileView.vue'
import type { Tile } from './types'
defineProps<{ tile: Tile; selected: boolean; disabled: boolean; drawn?: boolean; quick?: boolean }>()
defineEmits<{ pick: [tile: Tile] }>()
</script>
<template>
  <button class="ym-hand-tile" :class="{ 'ym-hand-tile-selected': selected, 'ym-drawn-tile': drawn }" :disabled="disabled" :aria-pressed="selected" :aria-label="`${drawn ? '刚摸到的' : ''}${tile.label}，${quick || selected ? '点击打出' : '点击选中'}`" @click="$emit('pick', tile)">
    <TileView :tile="tile" :selected="selected" /><span v-if="drawn" class="ym-drawn-label">摸牌</span>
  </button>
</template>

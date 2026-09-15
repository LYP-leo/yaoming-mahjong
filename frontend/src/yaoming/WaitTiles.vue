<script setup lang="ts">
import TileView from './TileView.vue'
import type { WaitHint } from './types'
defineProps<{ waits: WaitHint[] }>()

function fanLines(wait: WaitHint): string[] {
  if (wait.canRon && wait.canTsumo && wait.ronFan === wait.tsumoFan) return [`${wait.ronFan}番`]
  return [wait.canRon ? `点和${wait.ronFan}番` : '', wait.canTsumo ? `自摸${wait.tsumoFan}番` : ''].filter(Boolean)
}
function fanDescription(wait: WaitHint): string {
  return `${wait.tile.label}，${[wait.canRon ? `点和 ${wait.ronFan} 番` : '', wait.canTsumo ? `自摸 ${wait.tsumoFan} 番` : ''].filter(Boolean).join('，')}`
}
</script>
<template>
  <ul class="ym-hint-waits">
    <li v-for="wait in waits" :key="`${wait.tile.suit}-${wait.tile.rank}-${!!wait.tile.red}`" class="ym-hint-tile" :class="{ 'ym-hint-zero': wait.unseenCount === 0 }">
      <TileView :tile="wait.tile" small />
      <span class="ym-hint-count" :aria-label="`${wait.tile.label}尚未可见 ${wait.unseenCount} 张，可能在对手手中，不是牌墙剩余张数`" :title="`尚未可见 ${wait.unseenCount} 张，不是牌墙剩余张数`">{{ wait.unseenCount }}张</span>
      <span class="ym-hint-fans" :aria-label="fanDescription(wait)" :title="fanDescription(wait)"><span v-for="(line, index) in fanLines(wait)" :key="index">{{ line }}</span></span>
    </li>
  </ul>
</template>

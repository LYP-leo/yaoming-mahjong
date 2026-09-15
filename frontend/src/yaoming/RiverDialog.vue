<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import DiscardTile from './DiscardTile.vue'
import type { Player, RoomView } from './types'
defineProps<{ player: Player; lastDiscard?: RoomView['lastDiscard']; readonly?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLElement | null>(null)
let previousFocus: HTMLElement | null = null
onMounted(() => { previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; dialog.value?.focus() })
onUnmounted(() => previousFocus?.isConnected && previousFocus.focus())
function keyboard(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); emit('close'); return }
  if (event.key !== 'Tab') return
  const close = dialog.value?.querySelector<HTMLButtonElement>('button')
  if (close) { event.preventDefault(); close.focus() }
}
</script>
<template>
  <div class="ym-overlay ym-river-overlay" @click.self="emit('close')" @keydown="keyboard">
    <section ref="dialog" class="ym-entry-dialog ym-river-dialog" role="dialog" tabindex="-1" aria-modal="true" aria-labelledby="ym-river-title">
      <header class="ym-row"><h2 id="ym-river-title">{{ player.wind }}家 · {{ player.name }} 的牌河</h2><button class="ym-icon-button" aria-label="关闭完整牌河" @click="emit('close')">×</button></header>
      <p>{{ player.discards.length }} 张 · 按出牌先后排列，横杠表示摸切。已被吃碰杠取走的牌不重复显示。</p>
      <ol class="ym-full-river">
        <li v-for="(tile, index) in player.discards" :key="tile.id"><DiscardTile :tile="tile" :kind="player.discardKinds?.[tile.id]" :class="{ 'ym-latest-river-tile': !lastDiscard?.claimed && lastDiscard?.tile.id === tile.id }" /><small>{{ index + 1 }}</small></li>
      </ol>
      <p v-if="!player.discards.length">尚未出牌</p>
      <p class="ym-river-reminder">{{ readonly ? '只读历史牌河，展示所选复盘步骤的弃牌记录。' : '查看期间暂停自动摸牌，牌局倒计时仍会继续。' }}</p>
    </section>
  </div>
</template>

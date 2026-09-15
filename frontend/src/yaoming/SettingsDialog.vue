<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { usePlayPreferences } from './usePlayPreferences'
defineProps<{ playing: boolean }>()
const emit = defineEmits<{ close: [] }>()
const { autoDraw, quickDiscard } = usePlayPreferences()
const dialog = ref<HTMLElement | null>(null)
let previousFocus: HTMLElement | null = null
onMounted(() => { previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; dialog.value?.focus() })
onUnmounted(() => previousFocus?.focus())
function keyboard(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); emit('close'); return }
  if (event.key !== 'Tab') return
  const targets = [...(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled)') || [])]
  const first = targets[0], last = targets.at(-1)
  if (!first || !last) return
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog.value)) { event.preventDefault(); first.focus() }
}
</script>
<template>
  <div class="ym-overlay ym-settings-overlay" @click.self="emit('close')" @keydown="keyboard">
    <section class="ym-entry-dialog ym-settings-dialog" ref="dialog" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="ym-settings-title">
      <div class="ym-row"><span class="ym-overline">MAKE YOURSELF AT HOME</span><button class="ym-icon-button" aria-label="关闭设置" @click="emit('close')">×</button></div>
      <h2 id="ym-settings-title">游戏设置</h2><p>仅调整这台设备的操作习惯，不改变牌局规则。</p>
      <label class="ym-setting-switch"><span><strong>自动摸牌</strong><small>默认开启。轮到自己时自动摸一张；关闭后点击「摸牌」。不会自动出牌、吃碰、和牌或确认结算。</small></span><input v-model="autoDraw" type="checkbox" aria-label="自动摸牌" /></label>
      <label class="ym-setting-switch"><span><strong>快捷出牌</strong><small>单击手牌立即打出。默认关闭，选中后再次点击或按出牌按钮确认。</small></span><input v-model="quickDiscard" type="checkbox" aria-label="快捷出牌" /></label>
      <p v-if="playing" class="ym-settings-live" role="status">设置打开时暂停自动摸牌，但牌局倒计时仍会继续。</p>
      <p class="ym-settings-note">设置已即时生效，刷新页面后会保留。查看规则、牌谱或结算，以及暂离牌桌时，自动摸牌会暂停。</p>
      <button class="ym-button ym-primary ym-submit" @click="emit('close')">完成设置</button>
    </section>
  </div>
</template>
<style scoped>
.ym-settings-overlay { z-index: 65; }
.ym-settings-dialog { max-width: 510px; }
.ym-settings-dialog:focus { outline: none; }
.ym-app .ym-settings-dialog .ym-setting-switch { display: flex; align-items: center; gap: 22px; padding: 17px 0; margin: 0; border-top: 1px solid #dbe2cf; }
.ym-setting-switch > span { flex: 1; min-width: 0; }
.ym-setting-switch strong { display: block; font-size: 15px; color: #284837; }
.ym-setting-switch small { display: block; margin-top: 7px; font-size: 11px; line-height: 1.7; color: #7a8c72; }
.ym-app .ym-setting-switch input { width: 21px; height: 21px; flex-shrink: 0; padding: 0; margin: 0; accent-color: #487755; cursor: pointer; }
.ym-app .ym-settings-dialog .ym-settings-live { margin: 10px 0 15px; padding: 10px; background: #eae5d2; border-radius: 6px; font-size: 11px; color: #847343; }
.ym-app .ym-settings-dialog .ym-settings-note { font-size: 10px; margin: 14px 0 4px; }
</style>

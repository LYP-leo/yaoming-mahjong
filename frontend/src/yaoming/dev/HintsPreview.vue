<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import HintsPanel from '../HintsPanel.vue'
import { yaomingApi } from '../store'
import { room } from '../testFixtures'
import type { HintResponse, Tile } from '../types'
import '../yaoming.css'
import '../study.css'
import '../playability.css'
import '../player-lanes.css'
import '../stable-player-lanes.css'
import '../stable-controls.css'

const view = ref(room({ status: 'NEED_DRAW' }))
const requests = ref(0), failed = ref(false), discard = ref(false), listening = ref(false)
const left = ref(1), deferredResponse = ref<(() => void) | null>(null)
const fan = ref(4), mixedFans = ref(false)
const groups = ref(1)
const identity = { roomId: 'room1', playerId: 'p1', token: 'local-preview-not-a-real-seat' }
const bamboo: Tile = { id: 'fixture-b5', suit: 'BAMBOO', rank: 5, label: '五条' }
const dots: Tile = { id: 'fixture-d5', suit: 'DOTS', rank: 5, label: '五筒' }
const originalAdapter = yaomingApi.defaults.adapter
let holdNext = false
const adapter: typeof originalAdapter = async config => {
  requests.value++
  const version = view.value.version
  const waits = listening.value ? [
    { tile: bamboo, unseenCount: left.value, canTsumo: true, tsumoFan: fan.value, canRon: false, ronFan: 3, ronReason: '不足 4 番' },
    { tile: dots, unseenCount: 0, canTsumo: true, tsumoFan: mixedFans.value ? 5 : 4, canRon: true, ronFan: 4, ronReason: '' },
  ] : []
  const data: HintResponse = { roomId: identity.roomId, playerId: identity.playerId, version, analysis: {
    mode: discard.value ? 'DISCARD' : 'WAIT', waits: discard.value ? [] : waits,
    discards: discard.value ? Array.from({ length: groups.value }, (_, i) => ({ tile: { id: `discard-h${i + 1}`, suit: 'HONORS', rank: i + 1, label: ['东风', '南风', '西风'][i] }, waits })) : [],
    note: '测试数据，不应显示',
  } }
  if (holdNext) {
    holdNext = false
    await new Promise<void>(resolve => { deferredResponse.value = () => { deferredResponse.value = null; resolve() } })
  } else await new Promise(resolve => setTimeout(resolve, 100))
  if (failed.value) { failed.value = false; throw new Error('preview transient failure') }
  return { data, status: 200, statusText: 'OK', headers: {}, config }
}
yaomingApi.defaults.adapter = adapter
onUnmounted(() => { if (yaomingApi.defaults.adapter === adapter) yaomingApi.defaults.adapter = originalAdapter })
function update() { view.value = { ...view.value, version: view.value.version + 1 } }
function change() { left.value = left.value ? 0 : 1; update() }
function switchMode() { discard.value = !discard.value; view.value = { ...view.value, status: discard.value ? 'NEED_DISCARD' : 'NEED_DRAW' }; update() }
function toggleListening() { listening.value = !listening.value; update() }
function heartbeat() { view.value = { ...view.value, serverTime: new Date().toISOString() } }
function failOnce() { failed.value = true; update() }
function holdSame() { holdNext = true; update() }
function changeFan() { fan.value = fan.value === 4 ? 8 : 4; update() }
function splitFan() { mixedFans.value = !mixedFans.value; update() }
</script>
<template>
  <main class="ym-app hints-preview">
    <h1>稳定听牌验收</h1><p>仅本地测试夹具，不连接真实房间。下方标尺不应上下移动。</p>
    <div class="preview-controls"><button @click="toggleListening">切换有无听牌</button><button @click="change">变更五条张数</button><button @click="changeFan">只变更五条番数</button><button @click="splitFan">切换五筒不同番数</button><button @click="heartbeat">同版本心跳</button><button @click="switchMode">切换打后听牌</button><button @click="failOnce">一次网络失败</button><button @click="holdSame">暂停返回相同结果</button><button :disabled="!deferredResponse" @click="deferredResponse?.()">返回结果</button></div>
    <p class="preview-metrics">分析请求 {{ requests }} 次 · 当前版本 {{ view.version }}</p>
    <button @click="groups = groups % 3 + 1; update()">切换分组数量（{{ groups }}组）</button>
    <div class="preview-table-hints"><div class="ym-hand-hints"><HintsPanel :room="view" :identity="identity" /></div></div>
    <div class="preview-anchor">固定位置标尺</div>
  </main>
</template>
<style>
body { margin: 0; background: #f4f5ee; }
.hints-preview { padding: 24px; max-width: 600px; margin: 0 auto; min-height: 100vh; box-sizing: border-box; }
.hints-preview h1 { font-size: 24px; }
.hints-preview > p { font-size: 12px; margin: 16px 0; }
.preview-controls { display: flex; flex-wrap: wrap; gap: 8px; }
.preview-controls button { font: inherit; font-size: 12px; border: 1px solid #ccd1bc; padding: 8px; background: #f8f7ed; border-radius: 6px; }
.preview-anchor { margin-top: 16px; border-top: 2px dashed #59745c; padding: 12px; font-size: 12px; }
.preview-table-hints { margin-top: 12px; padding: 12px; background: #173f33; border-radius: 8px; }
</style>

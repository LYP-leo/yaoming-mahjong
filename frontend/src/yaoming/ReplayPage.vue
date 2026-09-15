<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { yaomingApi } from './store'
import { downloadReplay } from './replayExport'
import DiscardTile from './DiscardTile.vue'
import TableView from './TableView.vue'
import SettlementDialog from './SettlementDialog.vue'
import type { HandRecord, Identity, ReplayIdentity, ReplayList, RoomView } from './types'
import { capacityOf, ruleIdOf, ruleNameOf, windAt, UnsupportedRuleError } from './ruleProfiles'

const props = defineProps<{ identity: Identity | null; room: RoomView | null; history: ReplayIdentity[] }>()
const emit = defineEmits<{ close: []; forget: [identity: Identity] }>()
const keyOf = (seat: Pick<Identity, 'roomId' | 'playerId'>) => JSON.stringify([seat.roomId, seat.playerId])
const seats = computed(() => {
  if (!props.identity) return props.history
  const existing = props.history.find(seat => keyOf(seat) === keyOf(props.identity!))
  return [{ ...props.identity, roomName: props.room?.name || existing?.roomName || props.identity.roomId, playerName: props.room?.players.find(p => p.id === props.identity!.playerId)?.name || existing?.playerName || '当前座位', savedAt: Date.now() }, ...props.history.filter(seat => keyOf(seat) !== keyOf(props.identity!))]
})
const selectedKey = ref('')
const selectedSeat = computed(() => seats.value.find(seat => keyOf(seat) === selectedKey.value) || null)
const credentialKey = computed(() => selectedSeat.value ? JSON.stringify(selectedSeat.value && [selectedSeat.value.roomId, selectedSeat.value.playerId, selectedSeat.value.token]) : '')
const listing = ref<ReplayList | null>(null), record = ref<HandRecord | null>(null)
const loading = ref(false), loadingHand = ref(false), error = ref(''), exportNotice = ref('')
const selectedRound = ref(0), position = ref(0), playing = ref(false)
const perspectiveId = ref(''), resultOpen = ref(false), tableOverlayOpen = ref(false)
let listGeneration = 0, handGeneration = 0
let playback: ReturnType<typeof setInterval> | null = null
let wheelDistance = 0, lastWheelStepAt = -Infinity
const frame = computed(() => record.value?.frames[position.value])
const live = computed(() => !!props.room && !['WAITING', 'MATCH_END'].includes(props.room.status))
const actor = computed(() => frame.value?.players.find(p => p.seat === frame.value?.actorSeat)?.name || '系统')
const maximum = computed(() => Math.max(0, (record.value?.frames.length || 0) - 1))
const perspective = computed(() => frame.value?.players.find(player => player.id === perspectiveId.value)
  || frame.value?.players.find(player => player.id === selectedSeat.value?.playerId)
  || [...(frame.value?.players || [])].sort((a, b) => a.seat - b.seat)[0])
// This is a display adapter for an authenticated, completed historical hand, never a live action source.
const replayRoom = computed<RoomView | null>(() => {
  const hand = record.value, current = frame.value
  if (!hand || !current) return null
  const capacity = capacityOf(hand)
  return { id: hand.roomId, name: hand.roomName, ruleId: ruleIdOf(hand), ruleName: ruleNameOf(hand), capacity, version: current.index, status: current.status,
    round: hand.round, roundLabel: hand.roundLabel, dealerSeat: current.dealerSeat, currentSeat: current.currentSeat,
    wallCount: current.wallCount, message: current.message, meId: perspective.value?.id || '',
    players: current.players.map(player => ({ ...player, wind: windAt(player.seat, current.dealerSeat, capacity),
      handSize: player.hand.length, bot: false, ready: false, online: false, acknowledged: false, trustee: false,
      discardedCodes: [], passedCodes: [] })),
    actions: [], result: current.result, events: [{ sequence: current.index, text: current.message }],
    dice: current.dice, lastDiscard: current.lastDiscard, deadlineAt: null, deadlineKind: null, winHint: '',
  }
})
watch(() => props.identity ? keyOf(props.identity) : '', (key) => { if (key) selectedKey.value = key }, { immediate: true })
watch(seats, choices => { if (!choices.some(seat => keyOf(seat) === selectedKey.value)) selectedKey.value = choices[0] ? keyOf(choices[0]) : '' }, { immediate: true })
watch(credentialKey, () => { void loadList() }, { immediate: true })
onUnmounted(() => { listGeneration++; handGeneration++; pause() })
watch([resultOpen, tableOverlayOpen], () => { if (resultOpen.value || tableOverlayOpen.value) { pause(); resetWheel() } })

function failure(cause: unknown) {
  if (cause instanceof UnsupportedRuleError) return cause.message
  const response = (cause as { response?: { status?: number; data?: { message?: string } } }).response
  const status = response?.status
  return status === 401 || status === 403 || status === 400 && /身份|恢复码|已退出/.test(response?.data?.message || '') ? '无权查看这份牌谱：仅本场参赛者可使用自己的原座位身份访问。'
    : status === 404 ? '这份牌谱尚未生成或已超出保存范围。当前未结束小局不会公开，旧局也不会补造记录。'
    : '牌谱暂时加载失败，请重试；当前牌局不受影响。'
}
async function loadList() {
  const expected = ++listGeneration
  handGeneration++; pause(); resetWheel(); resultOpen.value = false; tableOverlayOpen.value = false; perspectiveId.value = ''
  listing.value = null; record.value = null; selectedRound.value = 0; position.value = 0; error.value = ''; loadingHand.value = false
  const seat = selectedSeat.value ? { ...selectedSeat.value } : null, key = credentialKey.value
  if (!seat) { loading.value = false; return }
  loading.value = true
  try {
    const { data } = await yaomingApi.get<ReplayList>('/replays', { params: { roomId: seat.roomId, playerId: seat.playerId }, headers: { 'X-Resume-Token': seat.token } })
    if (expected !== listGeneration || key !== credentialKey.value) return
    if (data.roomId !== seat.roomId) throw new Error('牌谱房间不一致')
    ruleIdOf(data)
    data.hands.forEach(ruleIdOf)
    listing.value = data
    const last = data.hands.at(-1)
    if (last) { selectedRound.value = last.round; void loadHand() }
  } catch (cause) { if (expected === listGeneration) error.value = failure(cause) }
  finally { if (expected === listGeneration) loading.value = false }
}
async function loadHand() {
  const expected = ++handGeneration
  pause(); resetWheel(); resultOpen.value = false; tableOverlayOpen.value = false; perspectiveId.value = ''
  record.value = null; position.value = 0; error.value = ''; exportNotice.value = ''
  const seat = selectedSeat.value ? { ...selectedSeat.value } : null, round = selectedRound.value, key = credentialKey.value
  if (!seat || !round) return
  loadingHand.value = true
  try {
    const { data } = await yaomingApi.get<HandRecord>(`/replays/${encodeURIComponent(seat.roomId)}/${round}`, { params: { playerId: seat.playerId }, headers: { 'X-Resume-Token': seat.token } })
    if (expected !== handGeneration || key !== credentialKey.value || round !== selectedRound.value) return
    if (!data.complete || data.roomId !== seat.roomId || data.round !== round) { error.value = '该小局尚未结束，不能查看完整手牌。'; return }
    ruleIdOf(data)
    record.value = data
  } catch (cause) { if (expected === handGeneration) error.value = failure(cause) }
  finally { if (expected === handGeneration) loadingHand.value = false }
}
function pause() { if (playback) clearInterval(playback); playback = null; playing.value = false }
function resetWheel() { wheelDistance = 0; lastWheelStepAt = -Infinity }
function seek(next: number) {
  if (!Number.isFinite(next)) return
  pause(); resetWheel(); resultOpen.value = false
  position.value = Math.min(maximum.value, Math.max(0, Math.trunc(next)))
}
function scrub(event: Event) { seek(Number((event.target as HTMLInputElement).value)) }
function changePerspective(event: Event) {
  perspectiveId.value = (event.target as HTMLSelectElement).value
  pause(); resetWheel()
}
function wheel(event: WheelEvent) {
  if (event.defaultPrevented || loading.value || loadingHand.value || !frame.value || maximum.value === 0
    || resultOpen.value || tableOverlayOpen.value || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return
  const target = event.target instanceof Element ? event.target : null
  if (target?.closest('input, textarea, select, button, a, [role="slider"], [contenteditable]:not([contenteditable="false"]), [role="dialog"], .ym-overlay')
    || document.querySelector('[role="dialog"][aria-modal="true"], .ym-overlay')) return
  if (!Number.isFinite(event.deltaY) || event.deltaY === 0 || Math.abs(event.deltaX) >= Math.abs(event.deltaY)) return
  const direction = Math.sign(event.deltaY)
  if (position.value + direction < 0 || position.value + direction > maximum.value) { wheelDistance = 0; return }
  // Keep an in-range gesture on the replay, including its small or throttled deltas;
  // native page scrolling resumes at a boundary or over protected controls.
  event.preventDefault()
  if (Date.now() - lastWheelStepAt < 100) return
  const distance = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? Math.max(1, window.innerHeight) : 1)
  if (Math.sign(wheelDistance) !== direction) wheelDistance = 0
  wheelDistance += distance
  if (Math.abs(wheelDistance) < 40) return
  pause(); position.value += direction; wheelDistance = 0; lastWheelStepAt = Date.now()
}
function play() {
  if (resultOpen.value || tableOverlayOpen.value) return
  if (playing.value) { pause(); return }
  if (!record.value || maximum.value === 0) return
  resetWheel()
  if (position.value >= maximum.value) position.value = 0
  playing.value = true
  playback = setInterval(() => { position.value = Math.min(maximum.value, position.value + 1); if (position.value >= maximum.value) pause() }, 1000)
}
function exportJson() {
  if (!record.value) return
  try { downloadReplay(record.value); exportNotice.value = `已导出 JSON（包含${capacityOf(record.value) === 4 ? '四' : '三'}家牌面与昵称，不包含恢复码或牌墙顺序）。` }
  catch { exportNotice.value = '浏览器未能下载文件，请稍后重试。' }
}
</script>

<template>
  <section class="ym-replay-page" @wheel="wheel">
    <div class="ym-row ym-replay-heading"><div><span class="ym-overline">REVIEW YOUR HANDS</span><h1>牌谱复盘</h1><p>一局结束，再看清每一步。仅向本场参赛者开放。</p></div><button class="ym-button" @click="emit('close')">返回{{ room ? '牌桌' : '大厅' }} →</button></div>
    <div v-if="live" class="ym-replay-live" role="status">你仍在 #{{ room!.id }} 的牌局中，操作和结算倒计时照常继续。建议先返回牌桌开启托管，再安心复盘；这里不会替你操作。</div>
    <div class="ym-replay-picker"><label>我的本机牌谱<select v-model="selectedKey" aria-label="选择牌谱房间"><option v-if="!seats.length" value="">暂无本机历史</option><option v-for="seat in seats" :key="keyOf(seat)" :value="keyOf(seat)">{{ seat.roomName }} · #{{ seat.roomId }} · {{ seat.playerName }}</option></select></label><button class="ym-button ym-compact" :disabled="loading || !selectedSeat" @click="loadList">{{ loading ? '加载中…' : '刷新牌谱' }}</button><button v-if="selectedSeat && (!identity || keyOf(selectedSeat) !== keyOf(identity))" class="ym-text-button" @click="emit('forget', selectedSeat)">移除这条本机记录</button></div>
    <p class="ym-replay-privacy">本机最多保存 20 个参赛身份用于取回牌谱，离开房间后仍可查看；不要在公共设备保留记录。服务器最多保留最近 20 场已归档牌谱，清理后将无法取回。</p>
    <p v-if="!seats.length" class="ym-replay-empty">完成一局后，牌谱会出现在这里。更新前的旧对局没有完整牌谱。</p>
    <div v-if="error" class="ym-replay-error" role="alert">{{ error }} <button class="ym-text-button" @click="loadList">重新加载</button></div>
    <p v-if="listing?.note" class="ym-replay-note">{{ listing.note }}</p>
    <div v-if="listing?.hands.length" class="ym-replay-hands"><label>选择小局<select v-model.number="selectedRound" aria-label="选择复盘小局" @change="loadHand"><option v-for="hand in listing.hands" :key="hand.round" :value="hand.round">{{ hand.roundLabel }} · {{ hand.title }} · {{ hand.frameCount }} 步{{ hand.incomplete ? '（记录不完整）' : '' }}</option></select></label><span v-if="loadingHand" role="status">正在加载本局…</span></div>
    <p v-else-if="listing && !loading" class="ym-replay-empty">暂无已结束小局。正在进行的牌局不会提前显示任何人的暗牌。</p>
    <template v-if="record">
      <p v-if="record.incomplete" class="ym-replay-note">这份牌谱记录不完整，可能来自旧局迁移或已达到帧数上限；缺失步骤不会被补造。</p>
      <div class="ym-replay-controls"><div class="ym-row"><strong>{{ record.roundLabel }} <small>{{ position + (record.frames.length ? 1 : 0) }} / {{ record.frames.length }} 步</small></strong><button class="ym-text-button" :disabled="!record.complete" @click="exportJson">导出 JSON ↓</button></div><input type="range" aria-label="复盘进度" :min="0" :max="maximum" :value="position" :disabled="!maximum" @input="scrub" /><div class="ym-replay-buttons"><button class="ym-button ym-compact" :disabled="position === 0" @click="seek(0)">首步</button><button class="ym-button ym-compact" :disabled="position === 0" @click="seek(position - 1)">上一步</button><button class="ym-button ym-primary ym-compact" :disabled="!maximum" @click="play">{{ playing ? '暂停' : '播放' }}</button><button class="ym-button ym-compact" :disabled="position >= maximum" @click="seek(position + 1)">下一步</button><button class="ym-button ym-compact" :disabled="position >= maximum" @click="seek(maximum)">末步</button></div><p v-if="exportNotice" role="status">{{ exportNotice }}</p></div>
      <div v-if="frame && replayRoom" class="ym-replay-frame" aria-label="只读牌谱牌桌">
        <div class="ym-play-layout ym-lanes-layout">
          <div class="ym-play-main"><TableView :room="replayRoom" :busy="false" readonly @overlay="tableOverlayOpen = $event" @result="resultOpen = true" /></div>
          <aside class="ym-sidebar ym-replay-sidebar" aria-label="复盘信息">
            <section class="ym-side-card ym-replay-step"><span :title="actor">第 {{ position + 1 }} 步 · {{ actor }}</span><strong :title="frame.message">{{ frame.message }}</strong><small>{{ new Date(frame.timestamp).toLocaleTimeString('zh-CN') }} · 牌墙 {{ frame.wallCount }} 张</small><p>只读复盘 · 滚轮向下看下一步，向上看上一步。</p></section>
            <section class="ym-side-card"><label class="ym-replay-perspective">查看视角<select aria-label="选择复盘视角" :value="perspective?.id || ''" @change="changePerspective"><option v-for="player in frame.players" :key="player.id" :value="player.id">{{ player.name }} · {{ windAt(player.seat, frame.dealerSeat, capacityOf(record)) }}位</option></select></label><p>{{ capacityOf(record) === 4 ? '四' : '三' }}家历史牌面全部展示，下方手牌跟随所选视角；这里不会执行任何对局操作。</p><p>{{ ruleNameOf(record) }}</p></section>
            <section v-if="frame.lastDiscard" class="ym-side-card ym-replay-latest"><DiscardTile :tile="frame.lastDiscard.tile" :kind="frame.lastDiscard.kind" small /><span>{{ frame.players.find(p => p.seat === frame!.lastDiscard!.fromSeat)?.name || '玩家' }} 的最新弃牌{{ frame.lastDiscard.claimed ? ' · 已被取走' : '' }}</span></section>
            <section v-if="frame.result" class="ym-side-card ym-replay-result"><h3>{{ frame.result.title }}{{ frame.result.draw ? '' : ` · ${frame.result.fan} 番` }}</h3><p>{{ frame.result.reason }}</p><p>{{ frame.result.items.map(item => `${item.name} ${item.fan}番`).join(' · ') }}</p><div><span v-for="score in frame.result.scores" :key="score.playerId">{{ score.name }} {{ score.score }} 点（{{ score.delta > 0 ? '+' : '' }}{{ score.delta }}）</span></div><button class="ym-text-button" @click="resultOpen = true">查看当时结算</button></section>
          </aside>
        </div>
      </div><p v-else class="ym-replay-empty">此份记录没有可播放的牌桌帧。</p>
    </template>
    <SettlementDialog v-if="resultOpen && replayRoom?.result" class="ym-replay-result-overlay" :room="replayRoom" :busy="false" readonly @minimize="resultOpen = false" />
  </section>
</template>

<style scoped>
.ym-app .ym-replay-page .ym-replay-frame { padding: 0; background: none; color: inherit; }
.ym-app .ym-replay-sidebar { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.ym-app .ym-replay-sidebar .ym-side-card { padding: 16px; min-width: 0; margin: 0; }
.ym-app .ym-replay-sidebar .ym-replay-step { display: grid; grid-template-rows: 20px 72px 20px minmax(0, 1fr); align-content: start; gap: 8px; height: 226px; box-sizing: border-box; overflow: hidden; }
.ym-app .ym-replay-sidebar .ym-replay-step > span, .ym-app .ym-replay-sidebar .ym-replay-step > small { color: #748466; }
.ym-app .ym-replay-sidebar .ym-replay-step > span, .ym-app .ym-replay-sidebar .ym-replay-step > small { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ym-app .ym-replay-sidebar .ym-replay-step > strong { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; color: #365b43; overflow-wrap: anywhere; line-height: 24px; overflow: hidden; }
.ym-app .ym-replay-sidebar .ym-replay-step > p { margin: 0; line-height: 18px; }
.ym-app .ym-replay-perspective { display: flex; flex-direction: column; gap: 8px; font-size: 12px; color: #506742; }
.ym-app .ym-replay-sidebar p { margin: 8px 0 0; overflow-wrap: anywhere; }
.ym-app .ym-replay-sidebar .ym-replay-latest { color: #58714f; border-color: #dce3d5; }
.ym-app .ym-replay-sidebar .ym-replay-result { border-color: #dce3d5; }
.ym-app .ym-replay-sidebar .ym-replay-result h3 { color: #405c3a; }
.ym-app .ym-replay-sidebar .ym-replay-result p { color: #748466; }
.ym-app .ym-replay-sidebar .ym-replay-result > div { flex-direction: column; gap: 6px; color: #4d6844; }
@media (max-width: 900px) { .ym-app .ym-replay-sidebar { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 540px) { .ym-app .ym-replay-sidebar { grid-template-columns: minmax(0, 1fr); } }
</style>

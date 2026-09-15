<script setup lang="ts">
import { computed, onUnmounted, shallowRef, watch } from 'vue'
import { yaomingApi } from './store'
import TileView from './TileView.vue'
import WaitTiles from './WaitTiles.vue'
import type { HintResponse, Identity, RoomView, Tile, WaitHint } from './types'

const props = defineProps<{ room: RoomView; identity: Identity | null }>()
const emit = defineEmits<{ sync: [] }>()
type Display = { waits: WaitHint[]; discards: { tile: Tile; waits: WaitHint[] }[] }
const empty = (): Display => ({ waits: [], discards: [] })
const shown = shallowRef<Display>(empty())
let shownKey = JSON.stringify(empty())
const seat = computed(() => JSON.stringify([props.identity?.roomId, props.identity?.playerId, props.identity?.token]))
const scope = computed(() => JSON.stringify([seat.value, props.room.id, props.room.meId, props.room.round, props.room.dealerSeat, props.room.ruleId || 'yaoming-3p']))
const eligible = computed(() => !!props.identity?.token && props.identity.roomId === props.room.id
  && props.identity.playerId === props.room.meId && props.room.players.some(player => player.id === props.room.meId)
  && ['NEED_DRAW', 'NEED_DISCARD', 'REACTION'].includes(props.room.status))
const context = computed(() => JSON.stringify([scope.value, props.room.version, props.room.status, eligible.value]))
const tileKey = (tile: Tile) => `${tile.suit}-${tile.rank}-${!!tile.red}`
const sortWaits = (waits: WaitHint[]) => [...new Map(waits.filter(wait => wait.canTsumo || wait.canRon)
  .map(wait => [tileKey(wait.tile), wait])).values()].sort((a, b) => tileKey(a.tile).localeCompare(tileKey(b.tile)))

function present(analysis: HintResponse['analysis']): Display {
  if (analysis.mode === 'WAIT') return { waits: sortWaits(analysis.waits), discards: [] }
  if (analysis.mode === 'DISCARD') return {
    waits: [], discards: [...new Map(analysis.discards.map(candidate => ({ tile: candidate.tile, waits: sortWaits(candidate.waits) }))
      .filter(candidate => candidate.waits.length > 0).map(candidate => [tileKey(candidate.tile), candidate])).values()]
      .sort((a, b) => tileKey(a.tile).localeCompare(tileKey(b.tile))),
  }
  return empty()
}
function publish(next: Display) {
  // Compare only what is drawn. An allowed fan change updates its text without
  // replacing keyed faces; hidden reasons, invalid-method fans, versions and
  // physical representative IDs do not touch an otherwise identical display.
  const waitsKey = (waits: WaitHint[]) => waits.map(wait => [tileKey(wait.tile), wait.unseenCount,
    wait.canRon ? wait.ronFan : null, wait.canTsumo ? wait.tsumoFan : null])
  const key = JSON.stringify({ waits: waitsKey(next.waits), discards: next.discards.map(candidate => [tileKey(candidate.tile), waitsKey(candidate.waits)]) })
  if (key !== shownKey) { shownKey = key; shown.value = next }
}

let generation = 0, retries = 0, retryAt = 0, disposed = false, loading = false
let lastSeat = seat.value, lastScope = scope.value, blockedSeat: string | null = null
let timer: ReturnType<typeof setTimeout> | null = null
let controller: AbortController | null = null
function cancel() {
  generation++
  if (timer !== null) clearTimeout(timer)
  timer = null; controller?.abort(); controller = null; loading = false
}
function schedule(delay = 200) {
  if (disposed || !eligible.value || blockedSeat === seat.value) return
  if (timer !== null) clearTimeout(timer)
  timer = setTimeout(() => { timer = null; void load() }, Math.max(delay, retryAt - Date.now()))
}
watch(context, () => {
  cancel()
  // Retain the last successful display through normal moves and background reads,
  // but never carry a previous seat/hand into a new one or across settlement.
  if (lastScope !== scope.value || !eligible.value) { publish(empty()); retries = 0; retryAt = 0 }
  lastScope = scope.value
  if (lastSeat !== seat.value) { lastSeat = seat.value; blockedSeat = null; retries = 0; retryAt = 0 }
  schedule()
}, { immediate: true })
onUnmounted(() => { disposed = true; cancel() })

function retryLater() {
  // No flashing error/retry UI. After two quick retries, recover quietly at most
  // once per 30 seconds; room version changes cannot bypass this cooldown.
  retryAt = Date.now() + [1000, 3000, 30000][Math.min(retries++, 2)]!
  schedule(0)
}
async function load() {
  if (disposed || !eligible.value || !props.identity || blockedSeat === seat.value || loading) return
  const identity = { ...props.identity }, version = props.room.version, expected = ++generation, key = context.value
  controller = new AbortController(); loading = true; retryAt = 0
  try {
    const { data } = await yaomingApi.get<HintResponse>(`/rooms/${encodeURIComponent(identity.roomId)}/hints`, {
      params: { playerId: identity.playerId }, headers: { 'X-Resume-Token': identity.token }, signal: controller.signal,
    })
    if (expected !== generation || key !== context.value) return
    if (data.roomId !== identity.roomId || data.playerId !== identity.playerId) {
      publish(empty()); blockedSeat = seat.value; emit('sync'); return
    }
    if (data.version !== version) { if (data.version > version) emit('sync'); retryLater(); return }
    publish(present(data.analysis)); retries = 0; retryAt = 0
  } catch (cause) {
    if (expected !== generation || key !== context.value) return
    const response = (cause as { response?: { status?: number; data?: { message?: string } } }).response
    const status = response?.status
    const invalidSeat = status === 400 && /身份无效|恢复码无效|玩家已退出/.test(response?.data?.message || '')
    if (invalidSeat || status === 401 || status === 403 || status === 404) {
      publish(empty()); blockedSeat = seat.value; emit('sync')
    } else retryLater()
  } finally { if (expected === generation) { loading = false; controller = null } }
}
</script>
<template>
  <section class="ym-side-card ym-hints" aria-label="听牌辅助">
    <div class="ym-hints-content" tabindex="0" aria-label="听牌牌面，可滚动查看">
      <WaitTiles v-if="shown.waits.length" :waits="shown.waits" />
      <div v-for="candidate in shown.discards" :key="tileKey(candidate.tile)" class="ym-discard-hint" :aria-label="`打出${candidate.tile.label}后的听牌`">
        <TileView :tile="candidate.tile" small />
        <svg class="ym-hint-arrow" viewBox="0 0 20 20" aria-hidden="true"><path d="M3 10h13m-5-5 5 5-5 5" fill="none" stroke="currentColor" stroke-width="1.5" /></svg>
        <WaitTiles :waits="candidate.waits" />
      </div>
    </div>
  </section>
</template>

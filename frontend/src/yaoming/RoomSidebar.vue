<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DiscardTile from './DiscardTile.vue'
import { useServerDeadline } from './useServerDeadline'
import type { Identity, RoomView } from './types'
import { capacityOf, minimumFanOf, playerCountLabel, ruleNameOf } from './ruleProfiles'

const props = defineProps<{ room: RoomView; identity: Identity | null }>()
const capacity = computed(() => capacityOf(props.room))
const emit = defineEmits<{ rules: []; replay: []; copy: [value: string, label: string] }>()
const { remaining, kindLabel, expired } = useServerDeadline(() => props.room)
const phase = computed(() => ({ WAITING: '等待入座', NEED_DRAW: '等待摸牌', NEED_DISCARD: '等待出牌',
  REACTION: '等待响应', HAND_END: '本局结算', MATCH_END: '本场结束' })[props.room.status] || props.room.status)
const currentPlayer = computed(() => props.room.players.find(player => player.seat === props.room.currentSeat))
const phaseText = computed(() => `${currentPlayer.value && ['NEED_DRAW', 'NEED_DISCARD'].includes(props.room.status) ? `${currentPlayer.value.name} · ` : ''}${phase.value}`)
const discardPlayer = computed(() => props.room.players.find(player => player.seat === props.room.lastDiscard?.fromSeat))
const discardText = computed(() => `${discardPlayer.value?.name || '玩家'} 打出`)
const openingPlayer = computed(() => props.room.players.find(player => player.seat === props.room.dice?.openingSeat))
const orderedEvents = computed(() => [...props.room.events].sort((first, second) => second.sequence - first.sequence))
const recentEvents = computed(() => orderedEvents.value.slice(0, 4))
const eventsExpanded = ref(false)
const recoveryOpen = ref(false)
// A stale identity from a different room/seat must never reveal or copy its credential here.
const seatIdentity = computed(() => props.identity?.roomId === props.room.id && props.identity.playerId === props.room.meId
  && props.room.players.some(player => player.id === props.room.meId) ? props.identity : null)
watch([() => props.room.id, () => props.room.meId, () => props.identity?.roomId, () => props.identity?.playerId, () => props.identity?.token], () => {
  recoveryOpen.value = false
})
watch(() => props.room.id, () => { eventsExpanded.value = false })
watch(() => props.room.events.length, count => { if (count <= 4) eventsExpanded.value = false })
function toggleRecovery(event: Event) { recoveryOpen.value = (event.currentTarget as HTMLDetailsElement).open }
function toggleEvents(event: Event) { eventsExpanded.value = (event.currentTarget as HTMLDetailsElement).open }
function copyRecovery() { if (recoveryOpen.value && seatIdentity.value) emit('copy', seatIdentity.value.token, '恢复码') }
</script>

<template>
  <aside class="ym-sidebar ym-room-sidebar" aria-label="牌局信息">
    <section class="ym-side-card ym-round-card" aria-label="本局状态">
      <span class="ym-overline">TABLE STATUS</span>
      <div class="ym-round-heading"><h2>{{ room.roundLabel }}</h2><p>牌山 <strong>{{ room.wallCount }}</strong> 张</p></div>
      <p class="ym-round-phase" :title="phaseText" :aria-label="phaseText">{{ phaseText }}</p>
      <div class="ym-round-clock" :class="{ 'ym-urgent': remaining !== null && remaining <= 5, 'ym-round-clock-empty': remaining === null }" :aria-hidden="remaining === null ? true : undefined">
        <template v-if="remaining !== null"><span>{{ expired ? '正在同步牌局' : `${kindLabel}时限` }}</span>
        <strong role="timer" aria-live="off" :aria-label="`${kindLabel}剩余${remaining}秒`">{{ remaining }}<small>秒</small></strong></template>
      </div>
      <div v-if="room.lastDiscard" class="ym-last-discard ym-sidebar-latest" :class="{ 'ym-last-discard-claimed': room.lastDiscard.claimed }" aria-label="最新弃牌">
        <DiscardTile :tile="room.lastDiscard.tile" :kind="room.lastDiscard.kind" small />
        <div class="ym-sidebar-discard-copy"><strong :title="discardText" :aria-label="discardText">{{ discardText }}</strong><span>{{ room.lastDiscard.claimed ? '已被取走' : '最新弃牌' }}</span></div>
      </div>
      <p v-else class="ym-sidebar-no-discard">尚无弃牌</p>
    </section>

    <section class="ym-side-card ym-sidebar-records" aria-label="牌桌记录">
      <h3>牌桌记录</h3>
      <ol v-if="!eventsExpanded && room.events.length" class="ym-sidebar-events ym-sidebar-recent" aria-label="最近四条记录">
        <li v-for="event in recentEvents" :key="event.sequence" :title="event.text"><span class="ym-sidebar-record-text">{{ event.text }}</span></li>
      </ol>
      <p v-if="!room.events.length" class="ym-sidebar-record-empty">等待第一位玩家行动。</p>
      <div class="ym-sidebar-record-disclosure">
      <details v-if="room.events.length > 4" class="ym-sidebar-full-records" :open="eventsExpanded" @toggle="toggleEvents">
        <summary>{{ eventsExpanded ? '收起完整记录' : `查看完整记录（${room.events.length}条）` }}</summary>
        <ol v-if="eventsExpanded" class="ym-sidebar-events" aria-label="完整牌桌记录"><li v-for="event in orderedEvents" :key="event.sequence" :title="event.text">{{ event.text }}</li></ol>
      </details>
      </div>
    </section>

    <section class="ym-side-card ym-sidebar-tools">
      <div class="ym-rule-mini"><span :title="ruleNameOf(room)">{{ playerCountLabel(capacity) }} · {{ capacity === 4 ? 136 : 108 }} 张</span><span>{{ minimumFanOf(room) }} 番起和 · 8 番封顶</span><span>10 点起步 · {{ capacity === 4 ? '东南八局' : '东南六局' }}</span></div>
      <button type="button" class="ym-text-button ym-sidebar-rules" @click="emit('rules')">查看完整规则 ↗</button>
      <div class="ym-replay-entry"><button type="button" class="ym-text-button" @click="emit('replay')">打开牌谱复盘 ↗</button><p>只开放已结束小局；复盘时牌局仍在计时。</p></div>
      <details v-if="seatIdentity" class="ym-recovery" :open="recoveryOpen" @toggle="toggleRecovery">
        <summary>我的座位恢复码</summary>
        <div v-if="recoveryOpen" class="ym-sidebar-recovery-body"><p>凭房间号与恢复码回到同一座位，请只为自己保存。</p><code>{{ seatIdentity.token }}</code><button type="button" class="ym-text-button" @click="copyRecovery">复制恢复码</button></div>
      </details>
      <details v-if="room.dice" class="ym-sidebar-dice">
        <summary>本局开门记录</summary>
        <p>第一次：{{ room.dice.opening.join(' · ') }}</p><p>第二次：{{ room.dice.breaking.join(' · ') }}</p>
        <p>开门位置：{{ openingPlayer?.wind ? `${openingPlayer.wind}位` : `座位 ${room.dice.openingSeat + 1}` }} · 第 {{ room.dice.breakStack }} 墩切墙</p>
      </details>
    </section>

    <section v-if="room.status === 'WAITING'" class="ym-side-card ym-invite-card">
      <h3>邀朋友一起开局</h3><p>分享房间号即可入座，也可由房主添加机器人补齐{{ playerCountLabel(capacity) }}。</p>
      <button type="button" class="ym-button" @click="emit('copy', room.id, '房间号')">复制房间号</button>
    </section>
  </aside>
</template>

<style scoped>
.ym-app .ym-room-sidebar { display: flex; flex-direction: column; gap: 12px; min-width: 0; align-self: start; }
.ym-app .ym-room-sidebar .ym-side-card { padding: 16px; min-width: 0; }
.ym-app .ym-room-sidebar h3 { margin-bottom: 9px; }
.ym-app .ym-room-sidebar p, .ym-app .ym-room-sidebar li, .ym-app .ym-room-sidebar summary { overflow-wrap: anywhere; }
.ym-app .ym-round-heading { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
.ym-app .ym-round-heading h2 { font-size: 24px; line-height: 1.3; color: #244e3d; margin: 0; }
.ym-app .ym-round-heading p { flex-shrink: 0; margin: 0; white-space: nowrap; }
.ym-app .ym-round-heading p strong { font-size: 19px; color: #3b624b; font-variant-numeric: tabular-nums; }
.ym-app .ym-round-card .ym-round-phase { height: 34px; margin: 5px 0 0; font-size: 11px; line-height: 17px; color: #597057; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
.ym-app .ym-round-clock { display: flex; align-items: center; justify-content: space-between; gap: 8px; height: 43px; box-sizing: border-box; padding-top: 9px; color: #6c7e63; font-size: 10px; }
.ym-app .ym-round-clock-empty { visibility: hidden; }
.ym-app .ym-round-clock strong { font: 28px/1.2 ui-monospace, monospace; font-variant-numeric: tabular-nums; color: #597047; }
.ym-app .ym-round-clock strong small { font: 10px sans-serif; margin-left: 5px; }
.ym-app .ym-round-clock.ym-urgent strong { color: #a15031; }
.ym-app .ym-room-sidebar .ym-sidebar-latest { display: flex; width: 100%; height: 58px; min-height: 58px; box-sizing: border-box; margin-top: 12px; padding: 8px; gap: 10px; overflow: visible; background: #dfe7d6; border: 1px solid #ccd8c0; }
.ym-app .ym-room-sidebar .ym-sidebar-latest.ym-last-discard-claimed { opacity: 1; }
.ym-app .ym-sidebar-latest :deep(.ym-tile-small) { width: 28px; height: 39px; }
.ym-app .ym-sidebar-discard-copy { min-width: 0; display: grid; grid-template-rows: 16px 14px; gap: 4px; }
.ym-app .ym-room-sidebar .ym-sidebar-discard-copy strong { color: #405e44; line-height: 16px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ym-app .ym-room-sidebar .ym-sidebar-discard-copy span { color: #728060; font-size: 10px; line-height: 14px; white-space: nowrap; }
.ym-app .ym-room-sidebar .ym-sidebar-no-discard { display: flex; align-items: center; height: 58px; min-height: 58px; box-sizing: border-box; margin: 12px 0 0; padding: 8px 0; color: #859279; }
.ym-app .ym-sidebar-events { list-style: none; padding: 0; margin: 0; height: auto; max-height: none; overflow: visible; color: #6f8267; font-size: 10px; }
.ym-app .ym-sidebar-events li { padding: 5px 0; border-bottom: 1px solid #dbe2d1; line-height: 1.5; }
.ym-app .ym-sidebar-events.ym-sidebar-recent { height: 140px; }
.ym-app .ym-sidebar-recent li { height: 35px; box-sizing: border-box; padding: 3px 0; line-height: 14px; }
.ym-app .ym-sidebar-record-text { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
.ym-app .ym-room-sidebar .ym-sidebar-record-empty { height: 140px; box-sizing: border-box; margin: 0; padding: 3px 0; }
.ym-app .ym-sidebar-record-disclosure { min-height: 32px; box-sizing: border-box; padding-top: 10px; }
.ym-app .ym-sidebar-full-records { margin: 0; }
.ym-app .ym-room-sidebar summary { cursor: pointer; line-height: 1.5; }
.ym-app .ym-room-sidebar summary:focus-visible { outline: 2px solid #b18c48; outline-offset: 3px; border-radius: 3px; }
.ym-app .ym-room-sidebar details[open] > summary { margin-bottom: 9px; }
.ym-app .ym-sidebar-tools .ym-rule-mini { margin: 0 0 4px; padding: 0; border: 0; }
.ym-app .ym-sidebar-tools .ym-replay-entry, .ym-app .ym-sidebar-tools > details { padding-top: 10px; margin-top: 10px; border-top: 1px solid #d8e0ce; }
.ym-app .ym-sidebar-tools .ym-replay-entry > button { margin-top: 0; }
.ym-app .ym-sidebar-tools .ym-replay-entry > p { font-size: 10px; margin: 5px 0 0; }
.ym-app .ym-room-sidebar .ym-recovery code { display: block; white-space: normal; overflow-wrap: anywhere; user-select: all; }
.ym-app .ym-sidebar-dice p { margin: 5px 0; }
@media (max-width: 900px) {
  .ym-app .ym-room-sidebar { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; }
}
@media (max-width: 540px) {
  .ym-app .ym-room-sidebar { grid-template-columns: minmax(0, 1fr); }
}
</style>

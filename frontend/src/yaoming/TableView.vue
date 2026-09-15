<script setup lang="ts">
import { computed, onMounted, onUnmounted, nextTick, ref, watch } from 'vue'
import TileView from './TileView.vue'
import PlayerLane from './PlayerLane.vue'
import RiverDialog from './RiverDialog.vue'
import HandTile from './HandTile.vue'
import { useServerDeadline } from './useServerDeadline'
import { useTableKeyboard } from './useTableKeyboard'
import { usePlayPreferences } from './usePlayPreferences'
import type { Action, RoomView, Tile } from './types'
import { capacityOf, playerCountLabel, seatWinds } from './ruleProfiles'
const props = defineProps<{ room: RoomView; busy: boolean; autoDrawNotice?: string; readonly?: boolean }>()
const emit = defineEmits<{ action: [action: Action]; result: []; panelSize: [height: number]; overlay: [open: boolean] }>()
const playerPanel = ref<HTMLElement | null>(null)
let panelObserver: ResizeObserver | null = null
onMounted(() => {
  if (typeof ResizeObserver === 'undefined' || !playerPanel.value) return
  panelObserver = new ResizeObserver(() => { if (playerPanel.value) emit('panelSize', Math.ceil(playerPanel.value.getBoundingClientRect().height)) })
  panelObserver.observe(playerPanel.value)
})
onUnmounted(() => panelObserver?.disconnect())
const me = computed(() => props.room.players.find(p => p.id === props.room.meId))
const capacity = computed(() => capacityOf(props.room))
const winHint = computed(() => {
  if (props.readonly || me.value?.trustee) return ''
  const hint = (props.room.winHint || '').trim()
  // The server's always-on rule summary is not a hand-specific assessment.
  // Filter only that known summary; keep actual wait, win and claim advice intact.
  const summary = /^\d+番起和[，,]8番封顶[；;]无振听[，,]打过或放过同种牌仍可点和[。.]?$/
  return summary.test(hint.replace(/\s/g, '')) ? '' : hint
})
const countLabel = computed(() => playerCountLabel(capacity.value))
const lanes = computed(() => seatWinds(capacity.value).map(wind => ({ wind, player: props.room.players.find(p => p.wind === wind) })))
const inspectedId = ref<string | null>(null)
const inspectedPlayer = computed(() => props.room.players.find(p => p.id === inspectedId.value))
watch([() => props.room.id, () => props.room.round, () => props.room.meId], () => { inspectedId.value = null })
watch(() => !!inspectedPlayer.value, open => emit('overlay', open))
onUnmounted(() => emit('overlay', false))
const { remaining, kindLabel, expired } = useServerDeadline(() => props.room)
const selectedId = ref<string | null>(null)
const { quickDiscard } = usePlayPreferences()
watch(quickDiscard, () => { selectedId.value = null })
const controls = computed(() => {
  if (props.readonly) return []
  const seenChi = new Set<string>()
  return props.room.actions.filter(a => {
    if (['DISCARD', 'LEAVE', 'TRUSTEE', 'ACK'].includes(a.type)) return false
    if (a.type !== 'CHI') return true
    const shape = optionTiles(a).map(t => `${t.suit}:${t.rank}`).sort().join('|')
    if (seenChi.has(shape)) return false
    seenChi.add(shape)
    return true
  })
})
const canDiscard = computed(() => !props.readonly && !me.value?.trustee && !expired.value && props.room.actions.some(a => a.type === 'DISCARD'))
const idleHint = computed(() => props.busy ? '正在提交…' : expired.value ? '时限已到，正在同步牌局' : me.value?.trustee ? '托管自动摸切中' : canDiscard.value ? '' : props.room.status === 'WAITING' ? me.value?.ready ? `你已准备，等待${countLabel.value}全部就绪` : '等待玩家入座，准备好即可开始' : '等待牌局推进')
const drawnTile = computed(() => me.value?.hand.find(t => t.id === me.value?.drawnTileId))
const sortedHand = computed(() => [...(me.value?.hand || [])].filter(t => t.id !== drawnTile.value?.id).sort((a, b) => {
  const suits = ['CHARACTERS', 'BAMBOO', 'DOTS', 'HONORS']
  return suits.indexOf(a.suit) - suits.indexOf(b.suit) || a.rank - b.rank
}))
function discardAction(tile: Tile) { return props.room.actions.find(a => a.type === 'DISCARD' && a.tileIds.includes(tile.id)) }
function optionTiles(action: Action) { return action.tileIds.map(id => me.value?.hand.find(t => t.id === id)).filter((t): t is Tile => !!t) }
const selectedTile = computed(() => me.value?.hand.find(t => t.id === selectedId.value))
const selectionContext = computed(() => JSON.stringify([
  props.room.id, props.room.round, props.room.status, props.room.currentSeat, props.room.meId, props.readonly,
  me.value?.drawnTileId, me.value?.trustee, me.value?.hand.map(t => t.id).sort(),
  me.value?.melds.map(m => [m.type, m.added, m.tiles.map(t => t.id)]),
  props.room.actions.filter(a => a.type === 'DISCARD').flatMap(a => a.tileIds).sort(),
]))
watch(selectionContext, () => {
  // Select the actual drawn entity, never a same-face tile or an arbitrary hand tile.
  // No action is emitted here; unchanged polls must preserve a player's later choice.
  const tile = drawnTile.value
  selectedId.value = tile && canDiscard.value && props.room.status === 'NEED_DISCARD'
    && props.room.currentSeat === me.value?.seat && discardAction(tile)?.tileIds.length === 1 ? tile.id : null
}, { immediate: true })
watch(expired, value => { if (value) selectedId.value = null })
function pick(tile: Tile) {
  if (props.busy || !canDiscard.value || !discardAction(tile)) return
  if (quickDiscard.value || selectedId.value === tile.id) submitDiscard(tile)
  else selectedId.value = tile.id
}
function submitDiscard(tile = selectedTile.value) {
  if (!tile || props.busy || !canDiscard.value) return
  const action = discardAction(tile)
  if (action) emit('action', action)
}
function send(action: Action) {
  if (props.readonly || props.busy || me.value?.trustee && !['TRUSTEE', 'READY', 'ADD_BOT'].includes(action.type) || expired.value && !['TRUSTEE', 'READY', 'ADD_BOT'].includes(action.type)) return
  emit('action', action)
}
const trusteeAction = computed(() => props.room.actions.find(a => a.type === 'TRUSTEE'))
const trusteeDescription = '自动摸牌后打出刚摸到的牌，响应一律过，不吃碰杠、不自动和牌；没有刚摸牌时，按手牌排序打出最右一张合法牌。'
const trusteeStatus = computed(() => me.value?.trusteeReason === 'TIMEOUT' ? '操作超时，托管自动摸切' : me.value?.trusteeReason === 'OFFLINE' ? '离线托管，自动摸切' : '托管中：自动摸切')
const ownStatus = computed(() => props.readonly ? '只读复盘' : me.value?.trustee ? trusteeStatus.value : props.room.status === 'WAITING' ? me.value?.ready ? '已准备，等待其他玩家' : '准备好即可开始' : ['HAND_END', 'MATCH_END'].includes(props.room.status) ? '等待结算确认' : props.room.currentSeat === me.value?.seat ? '轮到你了' : '等待其他玩家')

const displayHand = computed(() => [...sortedHand.value, ...(drawnTile.value ? [drawnTile.value] : [])])
function selectByKeyboard(tile: Tile) {
  selectedId.value = tile.id
  void nextTick(() => {
    const container = playerPanel.value?.querySelector<HTMLElement>('.ym-hand-scroll')
    const button = [...(container?.querySelectorAll<HTMLElement>('[data-hand-tile-id]') || [])].find(el => el.dataset.handTileId === tile.id)
    if (!container || !button) return
    const bounds = container.getBoundingClientRect(), target = button.getBoundingClientRect()
    if (target.left < bounds.left) container.scrollLeft -= bounds.left - target.left + 3
    else if (target.right > bounds.right) container.scrollLeft += target.right - bounds.right + 3
  })
}
useTableKeyboard(() => ({
  enabled: !props.readonly && !!me.value,
  canDiscard: canDiscard.value, busy: props.busy, expired: expired.value,
  trustee: !!me.value?.trustee, overlayOpen: !!inspectedPlayer.value,
  status: props.room.status, tiles: displayHand.value, selectedId: selectedId.value,
  actions: props.room.actions, revisionKey: JSON.stringify([props.room.id, props.room.meId, props.room.version]),
}), { select: selectByKeyboard, discard: submitDiscard, pass: send })
</script>
<template>
  <div class="ym-table-scroll"><section class="ym-table ym-lanes-table" :class="{ 'ym-four-player-table': capacity === 4 }" :style="{ '--ym-lane-count': capacity }" :aria-label="`${countLabel}麻将牌桌`">
    <template v-for="lane in lanes" :key="lane.wind">
      <PlayerLane v-if="lane.player" :player="lane.player" :self="lane.player.id === room.meId"
        :current="room.currentSeat === lane.player.seat" :status="room.status" :last-discard="room.lastDiscard" :reveal-hand="readonly" :capacity="capacity"
        @inspect="inspectedId = $event" />
      <article v-else class="ym-empty-seat ym-lane-empty" :aria-label="`${lane.wind}家空位`"><span class="ym-wind">{{ lane.wind }}</span><div><h3>等待玩家入座</h3><p>分享房间号，或添加机器人</p></div></article>
    </template>
  </section></div>
  <section class="ym-player-panel" ref="playerPanel" v-if="me" :class="{ 'ym-readonly-panel': readonly }">
    <div class="ym-own-info"><div class="ym-seat-info"><span class="ym-wind">{{ me.wind }}</span><div><strong>{{ me.name }} <small>{{ readonly ? '当前视角' : '你' }}</small></strong><small>{{ ownStatus }}</small></div><b>{{ me.score }}<small> 点</small></b></div><span class="ym-hand-hint">{{ canDiscard ? quickDiscard ? '单击即出牌' : '选牌后再确认' : `${me.handSize} 张手牌` }}</span></div>
    <div class="ym-hand-row"><div class="ym-hand-scroll" aria-label="手牌区域，可左右滑动" tabindex="0"><div class="ym-own-hand" aria-label="自己的手牌"><HandTile v-for="tile in sortedHand" :key="tile.id" :tile="tile" :data-hand-tile-id="tile.id" :selected="selectedId === tile.id" :disabled="busy || !canDiscard || !discardAction(tile)" :quick="quickDiscard" @pick="pick" /><p v-if="!sortedHand.length && !drawnTile">{{ room.status === 'WAITING' ? `${countLabel}就绪后，开始发牌` : '正在同步手牌…' }}</p></div></div><div class="ym-draw-slot" :aria-label="drawnTile ? '本次摸牌' : undefined"><HandTile v-if="drawnTile" :tile="drawnTile" :data-hand-tile-id="drawnTile.id" :selected="selectedId === drawnTile.id" :disabled="busy || !canDiscard || !discardAction(drawnTile)" :quick="quickDiscard" drawn @pick="pick" /></div></div>
    <div class="ym-hand-hints"><slot name="hints" /></div>
    <div class="ym-panel-notices" aria-label="和牌资格及操作提示" tabindex="0">
      <div v-if="!readonly && me.trustee" class="ym-trustee-banner" role="status" :title="trusteeDescription" :aria-label="`${trusteeStatus}。${trusteeDescription}`"><span>{{ trusteeStatus }}</span><button v-if="trusteeAction" class="ym-button ym-primary ym-compact" :disabled="busy" title="取消自动摸切托管，收回控制" aria-label="收回控制，取消自动摸切托管" @click="send(trusteeAction)">收回控制</button></div>
      <p v-if="winHint" class="ym-win-hint" :title="winHint">{{ winHint }}</p>
      <p v-if="!readonly && !me.trustee && autoDrawNotice" class="ym-win-hint" :title="autoDrawNotice" role="status">{{ autoDrawNotice }}</p>
    </div>
    <div class="ym-action-bar" aria-label="当前合法动作" tabindex="0" :style="{ '--ym-action-rows': Math.max(1, Math.ceil((controls.length + (canDiscard && !quickDiscard ? 1 : 0) + (room.result ? 1 : 0)) / 4)) }"><button v-if="canDiscard && !quickDiscard" class="ym-button ym-primary ym-confirm-discard" :disabled="busy || !selectedTile" @click="submitDiscard()">{{ selectedTile ? `打出 ${selectedTile.label}` : '请先选择一张牌' }}</button><button v-for="(action, i) in controls" :key="`${action.type}-${i}`" class="ym-button" :class="{ 'ym-primary': ['WIN', 'DRAW', 'READY'].includes(action.type), 'ym-claim-button': action.tileIds.length > 0 }" :disabled="busy || expired && !['READY', 'ADD_BOT'].includes(action.type) || me.trustee && !['READY', 'ADD_BOT'].includes(action.type)" @click="send(action)"><span class="ym-action-label" :title="action.label">{{ action.label }}</span><span v-if="action.tileIds.length && action.type !== 'ADD_BOT'" class="ym-action-tiles" :style="{ '--ym-action-tiles': optionTiles(action).length }"><TileView v-for="tile in optionTiles(action)" :key="tile.id" :tile="tile" small /></span></button><button v-if="room.result" class="ym-button ym-primary" @click="$emit('result')">查看结算</button><span v-if="!readonly && !canDiscard && !controls.length && !room.result && !selectedTile" class="ym-muted">{{ idleHint }}</span></div>
    <div class="ym-play-settings"><label v-if="!readonly"><input v-model="quickDiscard" type="checkbox" /> 快捷出牌 <span>（单击立即打出）</span></label><span v-if="remaining !== null" class="ym-mobile-deadline" :class="{ 'ym-urgent': remaining <= 5 }">{{ kindLabel }} {{ remaining }} 秒</span></div>
  </section>
  <RiverDialog v-if="inspectedPlayer" :player="inspectedPlayer" :last-discard="room.lastDiscard" :readonly="readonly" @close="inspectedId = null" />
</template>

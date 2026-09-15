<script setup lang="ts">
import { computed } from 'vue'
import TileView from './TileView.vue'
import MeldView from './MeldView.vue'
import DiscardTile from './DiscardTile.vue'
import type { Player, RoomView, Tile } from './types'

const props = withDefaults(defineProps<{ player: Player; self: boolean; current: boolean; status: string; lastDiscard?: RoomView['lastDiscard']; revealHand?: boolean; capacity?: number }>(), { revealHand: false, capacity: 3 })
const emit = defineEmits<{ inspect: [playerId: string] }>()
const active = computed(() => props.current && ['NEED_DRAW', 'NEED_DISCARD', 'REACTION'].includes(props.status))
const presence = computed(() => props.player.bot ? '机器人' : props.player.online ? '在线' : '暂时离线')
const occupiedSlots = computed(() => props.revealHand ? props.player.hand.length : props.player.handSize)
const handDescription = computed(() => props.revealHand ? `${props.player.name} 的复盘手牌，共 ${props.player.hand.length} 张`
  : `${props.self ? '你的' : `${props.player.name} 的`}暗牌，共 ${props.player.handSize} 张`)
const stateLabel = computed(() => {
  if (props.status === 'WAITING') return props.player.ready ? '已准备' : '等待准备'
  if (['HAND_END', 'MATCH_END'].includes(props.status)) return props.player.acknowledged ? '已确认结算' : '等待结算确认'
  if (!props.player.bot && props.player.trustee) return props.player.trusteeReason === 'TIMEOUT' ? '超时托管 · 自动摸切'
    : props.player.trusteeReason === 'OFFLINE' ? '离线托管 · 自动摸切' : '托管 · 自动摸切'
  if (active.value) return props.status === 'NEED_DRAW' ? '等待摸牌' : props.status === 'NEED_DISCARD' ? '等待出牌' : '等待响应'
  return '等待其他玩家'
})
const displayStatus = computed(() => props.revealHand ? '历史牌面' : `${presence.value} · ${stateLabel.value}`)
const riverStart = computed(() => Math.max(0, props.player.discards.length - 24))
const mobileStart = computed(() => Math.max(0, props.player.discards.length - 16))
const river = computed(() => props.player.discards.slice(riverStart.value).map((tile, index) => ({ tile, index: riverStart.value + index })))
const riverDescription = computed(() => `${props.player.name} 的牌河，共 ${props.player.discards.length} 张，按出牌先后顺序排列。`
  + (props.player.discards.length > 16 ? '桌面显示最近最多 24 张，窄屏显示最近最多 16 张；较早弃牌可通过查看全部牌河查看。' : ''))
function isLatest(tile: Tile) {
  return !props.lastDiscard?.claimed && props.lastDiscard?.fromSeat === props.player.seat && props.lastDiscard.tile.id === tile.id
}
</script>

<template>
  <article class="ym-player-lane" :class="{ 'ym-lane-self': self, 'ym-lane-current': active }" :aria-label="`${player.name} 的区域`" :data-player-id="player.id" :data-wind="player.wind">
    <header class="ym-lane-identity ym-seat-info">
      <span class="ym-wind" :aria-label="`${player.wind}位`">{{ player.wind }}</span>
      <div class="ym-lane-name"><strong :title="player.name">{{ player.name }} <small v-if="self" class="ym-lane-self-badge">{{ revealHand ? '当前视角' : '你' }}</small></strong><small class="ym-lane-status" :title="displayStatus">{{ displayStatus }}</small><span class="ym-lane-turn" :aria-hidden="active ? undefined : true">{{ active ? '行动中' : '' }}</span></div>
      <b class="ym-lane-score">{{ player.score }}<small> 点</small></b>
    </header>
    <div class="ym-lane-body">
      <div class="ym-lane-public">
        <div class="ym-concealed-hand ym-lane-hand-track" :style="{ '--ym-back-count': player.handSize }" :role="revealHand ? 'group' : 'img'" :aria-label="handDescription" :data-revealed="revealHand">
          <span v-for="n in 14" :key="n" class="ym-lane-hand-slot" :class="{ 'ym-lane-hand-slot-filled': n <= occupiedSlots }" :data-hand-slot="n" :data-tile-id="revealHand ? player.hand[n - 1]?.id : undefined" :aria-hidden="n > occupiedSlots ? true : undefined"><TileView v-if="revealHand && player.hand[n - 1]" :tile="player.hand[n - 1]" small /><TileView v-else-if="!revealHand && n <= player.handSize" back small aria-hidden="true" /></span>
        </div>
        <div class="ym-meld-strip" :aria-label="`${self ? '自己的' : `${player.name} 的`}副露区`"><MeldView v-for="(meld, index) in player.melds" :key="index" :meld="meld" :owner-seat="player.seat" :reveal="self || revealHand" :capacity="capacity" /></div>
      </div>
      <div class="ym-lane-river">
        <div class="ym-lane-river-heading"><span>牌河 <small>{{ player.discards.length }} 张</small><small v-if="player.discards.length > 24" class="ym-lane-river-wide-summary"> · 最近24</small><small v-if="player.discards.length > 16" class="ym-lane-river-mobile-summary"> · 最近16</small></span><button v-if="player.discards.length > 16" type="button" class="ym-lane-river-more ym-text-button" :aria-label="`查看${player.name}的全部牌河，共 ${player.discards.length} 张`" @click="emit('inspect', player.id)">查看全部 →</button></div>
        <ol class="ym-lane-river-tiles ym-river" :start="riverStart + 1" :aria-label="riverDescription">
          <li v-for="entry in river" :key="entry.tile.id" class="ym-lane-river-tile" :class="{ 'ym-lane-river-mobile-hidden': entry.index < mobileStart }" :value="entry.index + 1" :data-discard-index="entry.index + 1" :aria-label="`第 ${entry.index + 1} 张弃牌`" :title="`第 ${entry.index + 1} 张弃牌：${entry.tile.label}`"><DiscardTile :tile="entry.tile" :kind="player.discardKinds?.[entry.tile.id]" small :class="{ 'ym-latest-river-tile': isLatest(entry.tile) }" /></li>
        </ol>
      </div>
    </div>
  </article>
</template>

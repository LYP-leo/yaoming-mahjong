<script setup lang="ts">
import { computed } from 'vue'
import TileView from './TileView.vue'
import MeldView from './MeldView.vue'
import { useServerDeadline } from './useServerDeadline'
import type { Action, RoomView } from './types'
import './settlement.css'
import { capacityOf } from './ruleProfiles'
const props = defineProps<{ room: RoomView; busy: boolean; readonly?: boolean }>()
const emit = defineEmits<{ action: [action: Action]; minimize: [] }>()
const capacity = computed(() => capacityOf(props.room))
const result = computed(() => props.room.result)
const { remaining } = useServerDeadline(() => props.room)
const action = computed(() => !props.readonly && result.value && props.room.actions.find(a => a.type === (result.value!.matchOver ? 'LEAVE' : 'ACK')))
const waiting = computed(() => props.room.players.filter(p => !p.acknowledged && !p.bot))
const ownAcknowledged = computed(() => props.room.players.find(p => p.id === props.room.meId)?.acknowledged)
const hands = computed(() => result.value?.hands || [])
const winner = computed(() => result.value?.draw ? null : hands.value.find(hand => hand.playerId === result.value?.winnerId))
const winnerTiles = computed(() => winner.value?.hand || [])
const winnerMelds = computed(() => winner.value?.melds || [])
const winningTilePresent = computed(() => !!result.value?.winningTile && winnerTiles.value.some(tile => tile.id === result.value!.winningTile!.id))
const otherHands = computed(() => result.value?.draw ? hands.value : hands.value.filter(hand => hand.playerId !== result.value?.winnerId))
const playerName = (id: string) => result.value?.scores?.find(p => p.playerId === id)?.name || props.room.players.find(p => p.id === id)?.name || '玩家'
const ownerSeat = (id: string) => props.room.players.find(p => p.id === id)?.seat ?? 0
function confirm() {
  if (props.readonly || props.busy || !action.value) return
  emit('action', action.value)
}
</script>
<template>
  <div class="ym-overlay">
    <section class="ym-result-dialog ym-result-explained" :class="{ 'ym-four-player-result': capacity === 4 }" role="dialog" aria-modal="true" aria-labelledby="ym-result-title">
      <header class="ym-result-heading">
        <div class="ym-result-top"><span class="ym-overline">{{ room.roundLabel }} · {{ result?.matchOver ? 'MATCH COMPLETE' : 'HAND COMPLETE' }}</span><button class="ym-icon-button" aria-label="暂时收起结算" @click="$emit('minimize')">－</button></div>
        <h2 id="ym-result-title">{{ result?.title || '结算同步中' }}</h2><p class="ym-muted">{{ result?.reason || '正在等待本局服务器结算，请稍后同步。' }}</p>
        <p v-if="!readonly && result && !result.matchOver && room.deadlineKind === 'SETTLEMENT' && remaining !== null" class="ym-settlement-clock" role="timer">{{ remaining > 0 ? `${remaining} 秒后自动确认并继续下一局` : '确认时限已到，正在同步下一局' }}</p>
      </header>
      <div class="ym-result-content" tabindex="0" aria-label="本局结算详情">
        <template v-if="result">
          <template v-if="!result.draw">
            <div class="ym-result-winner"><div><small>和牌玩家</small><strong>{{ result.winnerId ? playerName(result.winnerId) : '未记录赢家' }}</strong><p v-if="winningTilePresent">和牌张：{{ result.winningTile?.label }} · 已在手牌中标出</p></div><strong class="ym-fan-total">{{ result.fan }} <span>番</span><small>服务器结算计番</small></strong></div>
            <div class="ym-winning-analysis">
              <section class="ym-winning-tiles" aria-labelledby="ym-winning-tiles-title">
                <h3 id="ym-winning-tiles-title">赢家终局牌面</h3>
                <template v-if="winner && (winnerTiles.length || winnerMelds.length)">
                  <div class="ym-winning-group-label"><span>手牌 · {{ winnerTiles.length }} 张</span><small>可左右滑动查看全部</small></div>
                  <div class="ym-winning-hand" tabindex="0" aria-label="赢家完整终局手牌">
                    <span v-for="tile in winnerTiles" :key="tile.id" class="ym-winning-tile" :class="{ 'ym-winning-tile-marked': tile.id === result.winningTile?.id }" :data-tile-id="tile.id" :data-winning-tile="tile.id === result.winningTile?.id ? 'true' : undefined" :aria-label="tile.id === result.winningTile?.id ? `${tile.label}，和牌张` : undefined"><TileView :tile="tile" small /><small v-if="tile.id === result.winningTile?.id" class="ym-winning-tile-label">和牌</small></span>
                  </div>
                  <div class="ym-winning-group-label"><span>副露 · {{ winnerMelds.length }} 组</span><small>暗杠在结算时完整展示</small></div>
                  <div class="ym-winning-melds" aria-label="赢家终局副露"><MeldView v-for="(meld, index) in winnerMelds" :key="index" :meld="meld" :owner-seat="ownerSeat(winner.playerId)" :capacity="capacity" reveal /><p v-if="!winnerMelds.length">无副露</p></div>
                  <p v-if="!winningTilePresent" class="ym-result-data-note">这份结算缺少可定位的和牌张记录，未补加任何牌。</p>
                </template>
                <p v-else class="ym-result-data-note">这份结算未记录赢家完整手牌，无法还原牌面。番数与点数仍按服务器结算展示。</p>
              </section>
              <section class="ym-fan-breakdown" aria-labelledby="ym-fan-breakdown-title">
                <h3 id="ym-fan-breakdown-title">番型与计番依据</h3>
                <ol v-if="result.items?.length" class="ym-fan-detail-list"><li v-for="(item, index) in result.items" :key="`${item.id}-${index}`"><div><strong>{{ item.name }}</strong><b>{{ item.fan }} 番</b></div><p>{{ item.description || '服务器未提供该番型的说明。' }}</p></li></ol>
                <p v-else class="ym-result-data-note">这份结算未附番型明细，不在前端推算补齐。</p>
                <div class="ym-fan-calculation"><p><span>原始总番</span><strong>{{ result.rawFan }} 番</strong></p><p><span>{{ result.rawFan > result.fan ? '封顶后计番' : '结算计番' }}</span><strong>{{ result.fan }} 番</strong></p><small>{{ result.rawFan > result.fan ? '本规则 8 番封顶；支付按封顶后的番数计算。' : '未触发 8 番封顶。' }}</small></div>
                <p class="ym-fan-authority">分项、总番与支付均采用本局服务器结算，不重新计算。</p>
              </section>
            </div>
          </template>
          <p v-else class="ym-draw-summary">本局流局，没有和牌玩家，不计算和牌番数。</p>
          <div class="ym-result-scores"><article v-for="score in result.scores || []" :key="score.playerId" :class="{ 'ym-is-me': score.playerId === room.meId }"><span>{{ result.matchOver ? `#${score.rank}` : '' }} {{ score.name }} <small v-if="score.playerId === room.meId">{{ readonly ? '当前视角' : '你' }}</small></span><strong>{{ score.score }}<small> 点</small></strong><b :class="score.delta > 0 ? 'ym-positive' : score.delta < 0 ? 'ym-negative' : ''">{{ score.delta > 0 ? '+' : '' }}{{ score.delta }}</b></article></div>
          <p v-for="(pay, i) in result.payments || []" :key="i" class="ym-payment">{{ playerName(pay.fromId) }} → {{ playerName(pay.toId) }}：{{ pay.amount }} 点<span v-if="pay.amount < pay.requested">（应付 {{ pay.requested }}，按剩余点数支付）</span></p>
          <details class="ym-result-hands"><summary>{{ result.draw ? `查看${capacity === 4 ? '四' : '三'}家终局手牌` : '查看其他玩家终局手牌' }}</summary><article v-for="hand in otherHands" :key="hand.playerId"><h4>{{ playerName(hand.playerId) }}</h4><div class="ym-reveal-hand"><TileView v-for="tile in hand.hand || []" :key="tile.id" :tile="tile" small /><MeldView v-for="(meld, i) in hand.melds || []" :key="i" :meld="meld" :owner-seat="ownerSeat(hand.playerId)" :capacity="capacity" reveal /></div></article><p v-if="!otherHands.length">没有其他终局手牌记录。</p></details>
        </template>
        <p v-else class="ym-result-data-note">结算数据暂未到达，不展示推测的手牌或番数。</p>
      </div>
      <div v-if="!readonly" class="ym-result-footer"><p>{{ result?.matchOver ? '本场结束，确认后返回大厅；不会自动退出。' : ownAcknowledged ? `已确认，等待${waiting.map(p => p.name).join('、') || '其他玩家'}。` : '所有玩家确认后继续，最多等待 60 秒。' }}</p><button v-if="action" class="ym-button ym-primary" :disabled="busy" @click="confirm">{{ result?.matchOver ? '确认结算 · 返回大厅' : '确认结算 · 准备下一局' }}</button><button v-else class="ym-button" disabled>{{ ownAcknowledged ? '已确认 · 等待其他玩家' : '等待结算同步' }}</button></div>
    </section>
  </div>
</template>

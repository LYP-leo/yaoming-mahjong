<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import TableView from '../TableView.vue'
import RoomSidebar from '../RoomSidebar.vue'
import WaitTiles from '../WaitTiles.vue'
import SettingsDialog from '../SettingsDialog.vue'
import SettlementDialog from '../SettlementDialog.vue'
import { room, player, result } from '../testFixtures'
import type { Action, DiscardKind, Meld, Tile, WaitHint } from '../types'
import { minimumFanOf, windAt } from '../ruleProfiles'
import '../yaoming.css'
import '../study.css'
import '../playability.css'
import '../player-lanes.css'
import '../stable-player-lanes.css'
import '../stable-controls.css'

const riverCount = ref(0), fullMelds = ref(false), longMessages = ref(false), manyActions = ref(false), trustee = ref(false), busy = ref(false), emptySeats = ref(false)
const fullRivers = computed({ get: () => riverCount.value > 0, set: value => { riverCount.value = value ? 60 : 0 } })
const panelHeight = ref(289), lastAction = ref('尚未点击动作'), hintCount = ref(0)
const showHints = computed({ get: () => hintCount.value > 0, set: value => { hintCount.value = value ? 18 : 0 } })
const settingsOpen = ref(false), settlementOpen = ref(false), waiting = ref(false), timed = ref(false)
const deadline = Date.now() + 3_600_000
const showDiscardKinds = ref(false)
const longNickname = ref(false), discardClaimed = ref(false)
const selfSeat = ref(0), dealerSeat = ref(0)
const capacity = ref<3 | 4>(3)
const ruleId = computed(() => capacity.value === 4 ? 'yaoming-4p' : 'yaoming-3p')
watch(capacity, count => { selfSeat.value %= count; dealerSeat.value %= count }, { flush: 'sync' })
const hasDraw = ref(true), otherActing = ref(false)
const makeTile = (prefix: string, i: number): Tile => ({ id: `${prefix}-${i}`, suit: i % 3 === 0 ? 'BAMBOO' : i % 3 === 1 ? 'DOTS' : 'HONORS', rank: i % 3 === 2 ? i % 7 + 1 : i % 9 + 1, label: `验收牌 ${i + 1}` })
const makeMeld = (owner: number, i: number): Meld => ({ type: 'KONG', tiles: Array.from({ length: 4 }, (_, n) => ({ ...makeTile(`meld-${owner}-${i}`, i), id: `meld-${owner}-${i}-${n}` })), fromSeat: (owner + 1) % capacity.value, claimedTileId: `meld-${owner}-${i}-0`, concealed: i === 1, added: i === 0 })
const view = computed(() => {
  const players = Array.from({ length: capacity.value }, (_, seat) => player(`p${seat + 1}`, seat)).map(p => ({
    ...p, wind: windAt(p.seat, dealerSeat.value, capacity.value),
    name: longNickname.value && p.seat === (selfSeat.value + 1) % capacity.value ? '这是用于验证最新弃牌条不会被很长的昵称撑开或产生滚动条的玩家名称' : `验收玩家${p.seat + 1}`, hand: p.seat === selfSeat.value && !waiting.value ? Array.from({ length: hasDraw.value ? 14 : 13 }, (_, i) => makeTile(`own-hand-${selfSeat.value}`, i)) : [],
    drawnTileId: p.seat === selfSeat.value && !waiting.value && hasDraw.value ? `own-hand-${selfSeat.value}-13` : null, handSize: waiting.value ? 0 : p.seat === selfSeat.value && hasDraw.value ? 14 : 13,
    discards: Array.from({ length: Math.min(60, Math.max(0, riverCount.value || 0)) }, (_, i) => makeTile(`river-${p.seat}`, i)),
    discardKinds: showDiscardKinds.value ? Object.fromEntries(Array.from({ length: Math.min(60, Math.max(0, riverCount.value || 0)) }, (_, i) => [`river-${p.seat}-${i}`, (i % 2 ? 'TSUMOGIRI' : 'TEDASHI') as DiscardKind])) : {},
    melds: fullMelds.value ? Array.from({ length: 4 }, (_, i) => makeMeld(p.seat, i)) : [],
    trustee: p.seat === selfSeat.value && trustee.value,
  }))
  const me = players[selfSeat.value], lastFrom = players[(selfSeat.value + 1) % capacity.value]
  const actions: Action[] = waiting.value ? [{ type: 'READY', label: '准备', tileIds: [] }, { type: 'ADD_BOT', label: '添加机器人', tileIds: [] }] : trustee.value ? [{ type: 'TRUSTEE', label: '收回控制', tileIds: [] }] : manyActions.value ? [
    ...Array.from({ length: 12 }, (_, i) => ({ type: 'KONG', label: `杠牌选择 ${i + 1}`, tileIds: me.hand.slice(i % 9, i % 9 + 4).map(t => t.id) })),
    { type: 'PASS', label: '过', tileIds: [] },
  ] : me.hand.map(t => ({ type: 'DISCARD', label: '出牌', tileIds: [t.id] }))
  return room({
    capacity: capacity.value, ruleId: ruleId.value,
    name: longNickname.value ? 'ABCDEFGHIJKLMNOPQRSTUVWXYZABCD' : '稳定布局验收牌桌',
    players: emptySeats.value ? [me] : players, meId: me.id, dealerSeat: dealerSeat.value, currentSeat: (selfSeat.value + (otherActing.value ? 1 : 0)) % capacity.value,
    version: (hasDraw.value ? 1 : 2) + (manyActions.value ? 4 : 0),
    actions, status: waiting.value ? 'WAITING' : manyActions.value ? 'REACTION' : 'NEED_DISCARD',
    deadlineAt: timed.value ? deadline : null, deadlineKind: timed.value ? 'DISCARD' : null,
    message: longMessages.value ? '其他玩家正在思考，等待回应。'.repeat(24) : '请出牌',
    winHint: longMessages.value ? (capacity.value === 4 ? '全不靠二番，加不求人一番可自摸和牌。' : '六字齐全不等于风龙，余牌须两顺子一雀头。').repeat(24)
      : !waiting.value && hasDraw.value && !otherActing.value ? `尚未成和牌形，至少需要${minimumFanOf({ ruleId: ruleId.value })}番`
      : `${minimumFanOf({ ruleId: ruleId.value })}番起和，8番封顶；无振听，打过或放过同种牌仍可点和`,
    lastDiscard: !emptySeats.value && lastFrom.discards.length ? { tile: lastFrom.discards.at(-1)!, fromSeat: lastFrom.seat, claimed: discardClaimed.value, kind: showDiscardKinds.value ? 'TSUMOGIRI' : null } : null,
    events: fullRivers.value ? Array.from({ length: 45 }, (_, sequence) => ({ sequence, text: `第 ${sequence + 1} 条桌面日志：玩家摸牌和出牌。` })) : [],
  })
})
const waits = computed<WaitHint[]>(() => Array.from({ length: hintCount.value }, (_, i) => ({ tile: { id: `hint-${i}`, suit: i < 9 ? 'BAMBOO' : 'DOTS', rank: i % 9 + 1, label: `${i % 9 + 1}${i < 9 ? '条' : '筒'}` }, unseenCount: i % 5, canTsumo: true, tsumoFan: i % 3 === 0 ? 5 : 4, canRon: i % 3 !== 2, ronFan: 4, ronReason: '' })))
const settlement = computed(() => {
  // Deliberately oversized visual fixture: this is not a legal combined hand.
  const hand = Array.from({ length: 14 }, (_, i) => makeTile('winner', i))
  return room({ ...view.value, status: 'HAND_END', deadlineAt: null, actions: [{ type: 'ACK', label: '确认结算', tileIds: [] }], result: result({ scores: view.value.players.map((p, i) => ({ playerId: p.id, name: p.name, score: p.score, delta: 0, rank: i + 1 })), winningTile: hand[13], fan: 8, rawFan: 24, items: Array.from({ length: 12 }, (_, i) => ({ id: `layout-fan-${i}`, name: `番型验收 ${i + 1}`, fan: 2, description: '用于检验多行番型说明与结算纵向滚动区域。这是布局样例，不计算真实番数。' })), hands: [{ playerId: 'p1', hand, melds: Array.from({ length: 4 }, (_, i) => makeMeld(0, i)) }, ...view.value.players.filter(p => p.id !== 'p1').map(p => p.id).map(playerId => ({ playerId, hand: Array.from({ length: 13 }, (_, i) => makeTile(playerId, i)), melds: [] }))] }) })
})
function report(action: Action) { lastAction.value = `${action.type} · ${action.label}（仅展示，不发送）` }
</script>
<template>
  <div class="ym-app stable-table-preview">
    <main class="ym-main">
      <header class="stable-preview-heading"><h1>稳定牌桌 · 隔离验收</h1><p>纯本地极限布局样例，不是真实对局；不连接 API，不读取或写入座位身份。大量牌河为每家 60 张视觉压力样例。</p></header>
      <div class="stable-preview-controls" aria-label="布局验收场景">
        <label>规则人数 <select v-model.number="capacity" aria-label="规则人数"><option :value="3">三人</option><option :value="4">四人</option></select></label>
        <button :aria-pressed="fullRivers" @click="fullRivers = !fullRivers">切换空 / 大量牌河</button>
        <label>每家弃牌张数 <input v-model.number="riverCount" type="number" min="0" max="60" aria-label="每家弃牌张数" /></label>
        <button :aria-pressed="fullMelds" @click="fullMelds = !fullMelds">切换空 / 四组副露</button>
        <button :aria-pressed="longMessages" @click="longMessages = !longMessages">切换短 / 长提示</button>
        <button :aria-pressed="manyActions" @click="manyActions = !manyActions">切换少 / 多动作</button>
        <button :aria-pressed="hasDraw" @click="hasDraw = !hasDraw">切换摸牌 / 打出摸牌</button>
        <button :aria-pressed="otherActing" @click="otherActing = !otherActing">切换行动玩家</button>
        <button :aria-pressed="trustee" @click="trustee = !trustee">切换托管</button>
        <button :aria-pressed="busy" @click="busy = !busy">切换提交中</button>
        <button :aria-pressed="emptySeats" @click="emptySeats = !emptySeats">切换空座</button>
        <button :aria-pressed="showHints" @click="showHints = !showHints">切换占位牌</button>
        <button :aria-pressed="hintCount === 1" @click="hintCount = hintCount === 1 ? 0 : 1">切换单张听牌</button>
        <button :aria-pressed="waiting" @click="waiting = !waiting">切换等待开局</button>
        <button :aria-pressed="timed" @click="timed = !timed">切换倒计时</button>
        <button @click="settingsOpen = true">打开游戏设置</button>
        <button @click="settlementOpen = true">打开结算压力样例</button>
        <button :aria-pressed="showDiscardKinds" @click="showDiscardKinds = !showDiscardKinds">切换手切 / 摸切标记</button>
        <button :aria-pressed="longNickname" @click="longNickname = !longNickname">切换超长昵称</button>
        <button :aria-pressed="discardClaimed" @click="discardClaimed = !discardClaimed">切换最新弃牌取走状态</button>
        <label>本人座位 <select v-model.number="selfSeat" aria-label="本人座位"><option v-for="n in capacity" :key="n" :value="n - 1">玩家{{ n }}</option></select></label>
        <label>当前庄家 <select v-model.number="dealerSeat" aria-label="当前庄家"><option v-for="n in capacity" :key="n" :value="n - 1">玩家{{ n }}</option></select></label>
      </div>
      <p class="stable-preview-output" role="status">{{ lastAction }}</p>
      <div class="ym-room-heading"><div><span class="ym-overline">AT THE TABLE</span><h1>{{ view.name }} <button class="ym-room-code">#room1 ↗</button></h1></div><div class="ym-row"><button class="ym-button ym-compact" @click="trustee = !trustee">{{ trustee ? '取消托管' : '开启托管' }}</button><button class="ym-button ym-compact" @click="lastAction = '暂离：仅展示，不发送'">暂离 · 返回大厅</button><button class="ym-button ym-compact" @click="lastAction = '离开：仅展示，不发送'">离开房间</button></div></div>
      <div class="ym-play-layout ym-lanes-layout" :style="{ '--ym-player-panel-height': `${panelHeight}px` }">
        <div class="ym-play-main"><TableView :room="view" :busy="busy" :auto-draw-notice="longMessages ? '自动摸牌提示，仅用于检查固定空间。'.repeat(10) : ''" @action="report" @panel-size="panelHeight = $event">
          <template #hints><section class="ym-side-card ym-hints" aria-label="听牌辅助"><div class="ym-hints-content" tabindex="0" aria-label="听牌牌面，可滚动查看"><WaitTiles v-if="waits.length" :waits="waits" /></div></section></template>
        </TableView></div>
        <RoomSidebar :room="view" :identity="null" @rules="lastAction = '查看规则：仅展示，不发送'" @replay="lastAction = '查看牌谱：仅展示，不发送'" @copy="(_, label) => lastAction = `${label}：仅展示，不复制`" />
      </div>
      <div class="stable-preview-anchor">牌桌之后的位置标尺</div>
    </main>
    <SettingsDialog v-if="settingsOpen" :playing="!waiting" @close="settingsOpen = false" />
    <SettlementDialog v-if="settlementOpen" :room="settlement" :busy="false" @minimize="settlementOpen = false" @action="settlementOpen = false" />
  </div>
</template>
<style>
body { margin: 0; min-width: 0; }
.stable-preview-heading h1 { font-size: 22px; }
.stable-preview-heading p { font-size: 12px; margin-top: 6px; }
.stable-preview-controls { display: flex; flex-wrap: wrap; gap: 6px; margin: 16px 0; }
.stable-preview-controls button { padding: 7px 10px; border: 1px solid #b8c6b3; background: #eef2e5; color: #355c43; font-size: 11px; }
.stable-preview-controls button[aria-pressed="true"] { background: #c4d8b9; }
.stable-preview-controls label { display: flex; align-items: center; gap: 5px; font-size: 11px; }
.stable-preview-controls input { width: 60px; padding: 4px; }
.stable-preview-output { min-height: 28px; font-size: 11px; }
.stable-preview-anchor { margin-top: 12px; padding: 10px 0; border-top: 2px dashed #668565; color: #668565; font-size: 11px; }
</style>

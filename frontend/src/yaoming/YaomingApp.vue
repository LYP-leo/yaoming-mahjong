<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useYaomingStore } from './store'
import { useBodyViewport } from './useBodyViewport'
import RulesPage from './RulesPage.vue'
import TableView from './TableView.vue'
import RoomSidebar from './RoomSidebar.vue'
import TileView from './TileView.vue'
import SettlementDialog from './SettlementDialog.vue'
import HintsPanel from './HintsPanel.vue'
import ReplayPage from './ReplayPage.vue'
import SettingsDialog from './SettingsDialog.vue'
import { usePlayPreferences } from './usePlayPreferences'
import { useAutoDraw } from './useAutoDraw'
import type { Action, RuleId } from './types'
import { capacityOf, ruleChoices, ruleIdOf, ruleNameOf, playerCountLabel } from './ruleProfiles'
import './yaoming.css'
import './playability.css'
import './study.css'
import './player-lanes.css'
import './stable-player-lanes.css'
import './stable-controls.css'

const store = useYaomingStore()
useBodyViewport()
const rulesOpen = ref(false)
const selectedRuleId = ref<RuleId>('yaoming-3p')
const viewedRuleId = ref<RuleId>('yaoming-3p')
const returnToCreate = ref(false)
const availableRules = computed(() => store.rulesets.length ? store.rulesets : ruleChoices)
const selectedCapacity = computed(() => selectedRuleId.value === 'yaoming-4p' ? 4 : 3)
function openRules(ruleId: RuleId = ruleIdOf(store.room)) {
  returnToCreate.value = false
  viewedRuleId.value = ruleId
  if (rulesOpen.value) void store.loadRules(ruleId)
  rulesOpen.value = true
}
function selectRules(ruleId: RuleId) { viewedRuleId.value = ruleId; void store.loadRules(ruleId) }
function createRuleDetails() { form.value = null; openRules(selectedRuleId.value); returnToCreate.value = true }
function closeRules() { rulesOpen.value = false; if (returnToCreate.value) { returnToCreate.value = false; form.value = 'create' } }
const replayOpen = ref(false)
const settingsOpen = ref(false)
const form = ref<'create' | 'join' | 'resume' | null>(null)
const entryDialog = ref<HTMLFormElement | null>(null)
let entryPointerStartedInside = false
const nickname = ref(localStorage.getItem('yaoming.nickname') || '')
const roomName = ref('今晚来一局')
const roomCode = ref('')
const recoveryToken = ref('')
const formError = ref('')
const copied = ref('')
const resultOpen = ref(true)
const leaveOpen = ref(false)
const detachOpen = ref(false)
const detachedLeaveOpen = ref(false)
const emptyRoomNotice = '房间没有在线真人持续 10 分钟后自动清理（默认设置；离线超过 60 秒视为不在线）。'
const tableOverlayOpen = ref(false)
const leaveAction = computed(() => store.room?.actions.find(a => a.type === 'LEAVE'))
const trusteeAction = computed(() => store.room?.actions.find(a => a.type === 'TRUSTEE'))
const trusteeDescription = computed(() => `${store.me?.trustee ? '取消托管，收回控制' : '开启托管自动摸切'}：自动摸牌后打出刚摸到的牌，响应一律过，不吃碰杠、不自动和牌；没有刚摸牌时，按手牌排序打出最右一张合法牌。`)
const highlights = [ { id: 'hero-1', suit: 'BAMBOO', rank: 1, label: '一条' }, { id: 'hero-2', suit: 'CHARACTERS', rank: 5, label: '五万' }, { id: 'hero-3', suit: 'DOTS', rank: 9, label: '九筒' } ]
const visibleRooms = computed(() => store.rooms.filter(r => r.status === 'WAITING'))
const { autoDraw } = usePlayPreferences()
const { notice: autoDrawNotice } = useAutoDraw(() => ({
  room: store.room, identity: store.identity, enabled: autoDraw.value,
  paused: tableOverlayOpen.value || rulesOpen.value || replayOpen.value || settingsOpen.value || leaveOpen.value || detachOpen.value || !!form.value || resultOpen.value && !!store.room?.result,
  connected: store.connected && !store.recoveryRequired, busy: store.busy, syncing: store.syncing,
}), action => store.act(action))

onMounted(() => {
  void store.start()
  document.addEventListener('pointerdown', trackEntryPointer, true)
  document.addEventListener('click', dismissEntryOutside, true)
})
onUnmounted(() => {
  document.removeEventListener('pointerdown', trackEntryPointer, true)
  document.removeEventListener('click', dismissEntryOutside, true)
  store.stop()
})
watch(() => store.room?.result, (value, old) => { if (value && (!old || JSON.stringify(value) !== JSON.stringify(old))) resultOpen.value = true })
watch(() => store.room?.id, id => { form.value = null; if (id || !store.identity) leaveOpen.value = false; resultOpen.value = true })
watch(() => store.identity, identity => { if (identity) detachedLeaveOpen.value = false })
watch(rulesOpen, (open) => { if (open) void store.loadRules(viewedRuleId.value) })
watch(rulesOpen, open => { if (open) replayOpen.value = false })
watch(replayOpen, open => { if (open) rulesOpen.value = false })

function openForm(type: 'create' | 'join' | 'resume', id = '') {
  entryPointerStartedInside = false
  formError.value = ''; store.error = ''; form.value = type; roomCode.value = id
}
function entryContains(event: Event) {
  return !!entryDialog.value && event.composedPath().includes(entryDialog.value)
}
function trackEntryPointer(event: PointerEvent) {
  entryPointerStartedInside = !!form.value && entryContains(event)
}
function dismissEntryOutside(event: MouseEvent) {
  const startedInside = entryPointerStartedInside
  entryPointerStartedInside = false
  // Capture outside clicks even when a notification above the scrim stops bubbling.
  // Dragging selected text from the form to the scrim must not discard the dialog.
  if (!form.value || !entryDialog.value || startedInside || entryContains(event)) return
  form.value = null
  event.preventDefault()
  event.stopPropagation()
}
async function submit() {
  formError.value = ''
  if (form.value !== 'resume' && !nickname.value.trim()) { formError.value = '先给自己起一个名字'; return }
  if (form.value !== 'create' && !roomCode.value.trim()) { formError.value = '请输入房间号'; return }
  if (form.value === 'resume' && !recoveryToken.value.trim()) { formError.value = '请输入座位恢复码'; return }
  if (form.value === 'create' && !roomName.value.trim()) { formError.value = '请输入房间名称'; return }
  localStorage.setItem('yaoming.nickname', nickname.value.trim())
  const ok = form.value === 'create' ? await store.create(roomName.value.trim(), nickname.value.trim(), selectedRuleId.value)
    : form.value === 'join' ? await store.join(roomCode.value, nickname.value.trim())
    : await store.resume(roomCode.value, recoveryToken.value)
  if (ok) form.value = null
}
async function copy(text: string, label: string) {
  try { await navigator.clipboard.writeText(text); copied.value = `${label}已复制` }
  catch { copied.value = '浏览器未允许复制，请手动选择并复制' }
  setTimeout(() => { copied.value = '' }, 3500)
}
async function send(action: Action) { await store.act(action) }
async function leave() { if (leaveAction.value && await store.act(leaveAction.value)) leaveOpen.value = false }
async function leaveDetached() { if (await store.leaveDetached()) detachedLeaveOpen.value = false }
function forgetDetached() { store.forgetDetached(); if (!store.detachedIdentity) detachedLeaveOpen.value = false }
function detach() { store.detach(); detachOpen.value = false }
</script>

<template>
  <div class="ym-app">
    <nav class="ym-nav" aria-label="主导航"><button class="ym-brand" @click="rulesOpen = false; replayOpen = false"><span class="ym-logo">要</span><span>要命麻将<small>YAOMING MAHJONG</small></span></button><div class="ym-nav-links"><button :class="{ 'ym-nav-active': !rulesOpen && !replayOpen }" @click="rulesOpen = false; replayOpen = false">{{ store.room ? '我的牌桌' : '游戏大厅' }}</button><button :class="{ 'ym-nav-active': replayOpen }" @click="replayOpen = true">牌谱复盘</button><button :class="{ 'ym-nav-active': rulesOpen }" @click="openRules()">规则手册</button><button @click="settingsOpen = true">设置</button></div><span class="ym-connection"><i :class="{ 'ym-offline': !store.connected }" />{{ store.connected ? store.pushHealthy ? '实时连接' : '已连接' : '连接中' }}</span></nav>
    <div v-if="store.error" class="ym-error" role="alert"><span>{{ store.error }}</span><button @click="store.retrySync()">重试同步</button><button aria-label="关闭错误提示" @click="store.error = ''">×</button></div>
    <div v-if="copied" class="ym-notice" role="status">{{ copied }}</div>
    <main class="ym-main">
      <ReplayPage v-if="replayOpen" :identity="store.identity" :room="store.room" :history="store.replayHistory" @close="replayOpen = false" @forget="store.removeHistory" />
      <RulesPage v-else-if="rulesOpen" :rules="store.rules" :rule-id="viewedRuleId" :choices="store.rulesets" @select="selectRules" @close="closeRules" />
      <template v-else-if="store.room">
        <div class="ym-room-heading"><div><span class="ym-overline">{{ store.room.status === 'WAITING' ? 'WAITING FOR PLAYERS' : 'AT THE TABLE' }}</span><h1>{{ store.room.name }} <button class="ym-room-code" @click="copy(store.room.id, '房间号')">#{{ store.room.id }} ↗</button></h1></div><div class="ym-row"><button v-if="trusteeAction" class="ym-button ym-compact ym-trustee-toggle" :disabled="store.busy" :title="trusteeDescription" :aria-label="trusteeDescription" @click="send(trusteeAction)">{{ store.me?.trustee ? '取消托管' : '开启托管' }}</button><template v-if="store.room.status === 'WAITING'"><button v-if="leaveAction" class="ym-button ym-compact ym-waiting-exit" :disabled="store.busy" @click="leave">退出房间 · 返回大厅</button></template><template v-else><button class="ym-button ym-compact" :disabled="store.busy" @click="detachOpen = true">暂离 · 返回大厅</button><button v-if="leaveAction" class="ym-button ym-compact" :disabled="store.busy" @click="leaveOpen = true">离开房间</button></template></div></div>
        <div class="ym-play-layout ym-lanes-layout">
        <div class="ym-play-main">
          <TableView :room="store.room" :busy="store.busy" :auto-draw-notice="autoDrawNotice" @action="send" @result="resultOpen = true" @overlay="tableOverlayOpen = $event">
            <template #hints><HintsPanel :room="store.room" :identity="store.identity" @sync="store.refresh()" /></template>
          </TableView>
        </div>
        <RoomSidebar :room="store.room" :identity="store.identity" @rules="openRules()" @replay="replayOpen = true" @copy="copy" />
      </div>
      </template>
      <section v-else-if="store.identity" class="ym-restoring"><span class="ym-overline">WELCOME BACK</span><h1>{{ store.recoveryRequired ? '这个座位已无法连接' : '正在回到你的牌桌' }}</h1><p>房间 #{{ store.identity.roomId }} · {{ store.recoveryRequired ? '房间已结束、已清理，或你的身份已失效' : '身份已保存' }}</p><button class="ym-button ym-primary" :disabled="store.syncing || store.busy" @click="store.refresh()">{{ store.syncing ? '同步中…' : '重新连接' }}</button><button v-if="store.recoveryRequired" class="ym-button" :disabled="store.busy" @click="store.forget()">清除失效座位，返回大厅</button><details><summary>房间已结束或需要换个身份？</summary><p>暂离只返回大厅，不会退出房间。可以在此标签页以新名字加入；其他标签页的玩家不会受到影响。</p><p>{{ emptyRoomNotice }}</p><code>{{ store.identity.token }}</code><button class="ym-button" :disabled="store.busy" @click="store.detach()">暂离并返回大厅</button></details></section>
      <template v-else>
        <div v-if="store.detachedIdentity" class="ym-detached-banner"><span>已暂离 #{{ store.detachedIdentity.roomId }}，尚未退出原房间。{{ emptyRoomNotice }}</span><div class="ym-detached-actions"><button class="ym-text-button" :disabled="store.busy" @click="store.resume(store.detachedIdentity.roomId, store.detachedIdentity.token)">返回原座位 →</button><button class="ym-text-button" :disabled="store.busy" @click="detachedLeaveOpen = true">退出原房间</button><button v-if="store.detachedRecoveryRequired" class="ym-text-button" :disabled="store.busy" @click="forgetDetached">清除失效记录</button></div></div>
        <section class="ym-hero"><div class="ym-hero-copy"><span class="ym-overline">YOUR TABLE. EVERY POINT COUNTS.</span><h1>三人或四人，<br />每一点都要命<span>。</span></h1><p>三人 108 张，4 番起和；四人 136 张，3 番起和。<br />10 点起手，邀朋友或与机器人来一场东南局。</p><div class="ym-hero-buttons"><button class="ym-button ym-primary" @click="openForm('create')">创建牌局 <span>↗</span></button><button class="ym-button" @click="openForm('join')">输入房间号</button></div><button class="ym-text-button" @click="openForm('resume')">已有座位？使用恢复码返回 →</button></div><div class="ym-hero-art" aria-hidden="true"><div class="ym-orbit ym-orbit-one" /><div class="ym-orbit ym-orbit-two" /><span class="ym-hero-stamp">三 / 四人<br />规则任选</span><div class="ym-hero-tiles"><TileView v-for="tile in highlights" :key="tile.id" :tile="tile" /></div><span class="ym-art-caption">3 OR 4 PLAYERS · EVERY POINT COUNTS</span></div></section>
        <div class="ym-lobby-facts"><span><b>01</b> 三人保留 159 万，四人使用全副牌</span><span><b>02</b> 会吃会碰，就能上桌</span><span><b>03</b> 每局所有玩家共同确认结算</span><button class="ym-text-button" @click="openRules()">读规则手册 ↗</button></div>
        <section class="ym-rooms-section"><div class="ym-row"><div><span class="ym-overline">FIND YOUR TABLE</span><h2>正在等你入座 <small>{{ visibleRooms.length }}</small></h2></div><button class="ym-text-button" @click="store.loadLobby()">刷新房间 ↻</button></div><div v-if="visibleRooms.length" class="ym-room-list"><article v-for="room in visibleRooms" :key="room.id"><div class="ym-room-list-top"><span>{{ ruleNameOf(room) }}</span><small>#{{ room.id }}</small></div><h3>{{ room.name }}</h3><div class="ym-room-list-bottom"><span class="ym-seat-dots"><i v-for="n in capacityOf(room)" :key="n" :class="{ 'ym-filled': n <= room.players }" /> {{ room.players }} / {{ capacityOf(room) }} 人</span><button class="ym-button ym-compact" :disabled="room.players >= capacityOf(room)" @click="openForm('join', room.id)">入座 →</button></div></article></div><div v-else class="ym-no-rooms"><div class="ym-empty-symbol">＋</div><div><h3>现在是开一桌的好时候</h3><p>创建房间后，邀请朋友或添加机器人即可开始。</p></div><button class="ym-button" @click="openForm('create')">创建第一桌</button></div></section>
        <footer class="ym-footer"><span>要命麻将 · 规则 Version 26.9 LTS</span></footer>
      </template>
    </main>
    <div v-if="form" class="ym-overlay ym-entry-overlay"><form ref="entryDialog" class="ym-entry-dialog" role="dialog" aria-modal="true" aria-labelledby="ym-entry-title" @submit.prevent="submit"><div class="ym-row"><span class="ym-overline">LET'S PLAY MAHJONG</span><button type="button" class="ym-icon-button" aria-label="关闭入座窗口" @click="form = null">×</button></div><h2 id="ym-entry-title">{{ form === 'create' ? '定下今晚这桌。' : form === 'join' ? '你的座位，留好了。' : '欢迎回到牌桌。' }}</h2><p>{{ form === 'create' ? `${playerCountLabel(selectedCapacity)}就绪后开局，房间规则创建后固定。` : form === 'join' ? '输入房间号，与朋友一起开始。' : '使用之前保存的房间号和座位恢复码。' }}</p><label v-if="form !== 'resume'">你的名字<input v-model="nickname" maxlength="20" placeholder="怎么称呼你" autofocus required /></label><label v-if="form === 'create'">房间名称<input v-model="roomName" maxlength="30" required /></label><label v-else>房间号<input v-model="roomCode" maxlength="40" placeholder="例如 123456" required /></label><label v-if="form === 'resume'">座位恢复码<input v-model="recoveryToken" autocomplete="off" placeholder="粘贴你保存的恢复码" required /></label><div v-if="form === 'create'" class="ym-create-rule"><label>牌局规则<select v-model="selectedRuleId" aria-label="选择牌局规则"><option v-for="rule in availableRules" :key="rule.id" :value="rule.id">{{ rule.name }}</option></select></label><button type="button" class="ym-text-button" @click="createRuleDetails">查看所选规则详情 →</button></div><p v-if="formError || store.error" class="ym-form-error" role="alert">{{ formError || store.error }}</p><button class="ym-button ym-primary ym-submit" :disabled="store.busy" type="submit">{{ store.busy ? '正在连接…' : form === 'create' ? '创建并入座 →' : form === 'join' ? '加入牌局 →' : '恢复我的座位 →' }}</button></form></div>
    <div v-if="leaveOpen" class="ym-overlay"><section class="ym-entry-dialog" role="dialog" aria-modal="true" aria-labelledby="ym-leave-title"><h2 id="ym-leave-title">离开这张牌桌？</h2><p>这会退出房间。对局中离开会交由机器人继续，无法使用原恢复码返回座位。若只是暂时离开屏幕，可以取消并开启托管。</p><p v-if="store.error" class="ym-form-error" role="alert">{{ store.error }}</p><div class="ym-row"><button class="ym-button" :disabled="store.busy" @click="leaveOpen = false">留在牌桌</button><button class="ym-button ym-primary" :disabled="store.busy" @click="leave">确认离开</button></div></section></div>
    <div v-if="detachOpen" class="ym-overlay"><section class="ym-entry-dialog" role="dialog" aria-modal="true" aria-labelledby="ym-detach-title"><h2 id="ym-detach-title">暂离，不退出房间。</h2><p>返回大厅后可用新名字入座；同一浏览器的其他标签页保持各自身份。原牌局会继续，离线超过 60 秒后托管自动摸切，不吃碰杠、不自动和牌。</p><p>{{ emptyRoomNotice }}房间仍存在时可使用恢复码返回。</p><p>房间 #{{ store.identity?.roomId }} · 请保存恢复码</p><code>{{ store.identity?.token }}</code><div class="ym-row ym-detach-actions"><button class="ym-button" :disabled="store.busy" @click="detachOpen = false">继续游戏</button><button class="ym-button ym-primary" :disabled="store.busy" @click="detach">暂离并返回大厅</button></div></section></div>
    <div v-if="detachedLeaveOpen && store.detachedIdentity" class="ym-overlay"><section class="ym-entry-dialog" role="dialog" aria-modal="true" aria-labelledby="ym-detached-leave-title"><h2 id="ym-detached-leave-title">退出原房间 #{{ store.detachedIdentity.roomId }}？</h2><p>等待中的座位会立即释放；如果牌局已经开始，会由机器人继续，原恢复码将无法返回座位。这不是暂离。</p><p v-if="store.error" class="ym-form-error" role="alert">{{ store.error }}</p><div class="ym-row ym-detach-actions"><button class="ym-button" :disabled="store.busy" @click="detachedLeaveOpen = false">取消</button><button class="ym-button ym-primary" :disabled="store.busy" @click="leaveDetached">确认退出原房间</button><button v-if="store.detachedRecoveryRequired" class="ym-button" :disabled="store.busy" @click="forgetDetached">清除失效记录</button></div></section></div>
    <SettlementDialog v-if="!rulesOpen && !replayOpen && resultOpen && store.room?.result" :room="store.room" :busy="store.busy" @action="send" @minimize="resultOpen = false" />
    <SettingsDialog v-if="settingsOpen" :playing="!!store.room && !['WAITING', 'MATCH_END'].includes(store.room.status)" @close="settingsOpen = false" />
  </div>
</template>

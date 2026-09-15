<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { AxiosError, type AxiosAdapter } from 'axios'
import ReplayPage from '../ReplayPage.vue'
import { yaomingApi } from '../store'
import { useBodyViewport } from '../useBodyViewport'
import type { HandRecord, Identity, ReplayFrame, ReplayIdentity, ReplayList, ReplayPlayer, Result, Tile } from '../types'
import '../yaoming.css'
import '../playability.css'
import '../study.css'
import '../player-lanes.css'
import '../stable-player-lanes.css'
import '../stable-controls.css'

useBodyViewport()
const notice = ref('滚轮切步、切换三家视角、展开完整牌河，或跳到末步查看结算。')
const readCount = ref(0)
const identity: Identity = { roomId: 'room1', playerId: 'sample-p1', token: 'synthetic-preview-only-not-a-server-credential' }
const startedAt = Date.UTC(2026, 8, 11, 4, 0)
const history: ReplayIdentity[] = [{ ...identity, roomName: '本地合成牌谱', playerName: '样例东家', savedAt: startedAt }]
const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T

// A real 108-tile set with unique physical IDs: every frame conserves the same tiles.
// The eight illustrative snapshots start mid-hand; they are not a server game or historical evidence.
const codes = ['W1', 'W5', 'W9', ...Array.from({ length: 9 }, (_, i) => `B${i + 1}`),
  ...Array.from({ length: 9 }, (_, i) => `D${i + 1}`), 'H1', 'H2', 'H3', 'H5', 'H6', 'H7']
const suits: Record<string, string> = { W: 'CHARACTERS', B: 'BAMBOO', D: 'DOTS', H: 'HONORS' }
const deck: Tile[] = codes.flatMap(code => Array.from({ length: 4 }, (_, index) => {
  const rank = Number(code[1]), suit = suits[code[0]]
  const label = code[0] === 'H' ? ['东', '南', '西', '北', '中', '发', '白'][rank - 1]
    : `${rank}${({ W: '万', B: '条', D: '筒' } as Record<string, string>)[code[0]]}`
  return { id: `replay-layout-${code}-${index}`, suit, rank, label }
}))
function take(code: string): Tile {
  const index = deck.findIndex(tile => tile.suit === suits[code[0]] && tile.rank === Number(code[1]))
  if (index < 0) throw new Error(`本地样例牌数超限：${code}`)
  return deck.splice(index, 1)[0]
}
const players: ReplayPlayer[] = [
  { id: identity.playerId, name: '样例东家', seat: 0, score: 10,
    hand: ['D1', 'D2', 'D3', 'D4', 'D5', 'D6', 'D7', 'D8', 'D9', 'D2', 'D3', 'D5', 'H3'].map(take),
    discards: [], melds: [], drawnTileId: null, discardKinds: {} },
  { id: 'sample-p2', name: '样例南家', seat: 1, score: 10,
    hand: ['H3', 'H3', 'B1', 'B2', 'B3', 'B4', 'B5', 'B6', 'W1', 'W5', 'W9', 'H5', 'H7'].map(take),
    discards: [], melds: [], drawnTileId: null, discardKinds: {} },
  { id: 'sample-p3', name: '样例西家', seat: 2, score: 10,
    hand: ['B7', 'B8', 'B9', 'B2', 'B3', 'B4', 'D6', 'D7', 'D8', 'H1', 'H1', 'H6', 'H6'].map(take),
    discards: [], melds: [], drawnTileId: null, discardKinds: {} },
]
const firstDraw = take('D5'), winningDraw = take('D1')
players.forEach((player, index) => {
  player.discards = deck.splice(0, [20, 5, 3][index])
  player.discardKinds = Object.fromEntries(player.discards.map((tile, order) => [tile.id, order % 2 ? 'TSUMOGIRI' : 'TEDASHI']))
})
let wallCount = deck.length + 2
let lastDiscard: ReplayFrame['lastDiscard'] = null
const frames: ReplayFrame[] = []
function snapshot(type: string, actorSeat: number, message: string, status: string, currentSeat: number, result: Result | null = null) {
  const frame: ReplayFrame = copy({ index: frames.length, timestamp: startedAt + frames.length * 2000,
    type, actorSeat, message, status, currentSeat, wallCount, dealerSeat: 0, players, lastDiscard,
    dice: { opening: [2, 3], breaking: [4, 1], openingSeat: 0, breakStack: 5 }, result })
  const physical = frame.players.flatMap(player => [...player.hand, ...player.discards, ...player.melds.flatMap(meld => meld.tiles)])
  if (physical.length + wallCount !== 108 || new Set(physical.map(tile => tile.id)).size !== physical.length)
    throw new Error('本地牌谱样例不满足物理牌守恒')
  frames.push(frame)
}
snapshot('PREVIEW_START', 0, '合成关键帧：三家各 13 张手牌，东家准备摸牌。', 'NEED_DRAW', 0)
players[0].hand.push(firstDraw); players[0].drawnTileId = firstDraw.id; wallCount--
snapshot('DRAW', 0, '东家摸入 5 筒：13 → 14 张，摸牌独立显示。', 'NEED_DISCARD', 0)
const west = players[0].hand.find(tile => tile.suit === 'HONORS' && tile.rank === 3)!
players[0].hand = players[0].hand.filter(tile => tile.id !== west.id)
players[0].drawnTileId = null; players[0].discards.push(west); players[0].discardKinds![west.id] = 'TEDASHI'
lastDiscard = { tile: west, fromSeat: 0, claimed: false, kind: 'TEDASHI' }
snapshot('DISCARD', 0, '东家手切西：新摸的 5 筒保留在手牌中。', 'REACTION', 0)
const westPair = players[1].hand.filter(tile => tile.suit === 'HONORS' && tile.rank === 3)
players[1].hand = players[1].hand.filter(tile => !westPair.some(pair => pair.id === tile.id))
players[1].melds.push({ type: 'PONG', tiles: [...westPair, west], fromSeat: 0, claimedTileId: west.id, concealed: false })
players[0].discards = players[0].discards.filter(tile => tile.id !== west.id)
lastDiscard = { ...lastDiscard, claimed: true }
snapshot('PONG', 1, '南家碰西：西从东家牌河移至南家副露，手牌为 11 张。', 'NEED_DISCARD', 1)
const southDiscard = players[1].hand.find(tile => tile.suit === 'HONORS' && tile.rank === 7)!
players[1].hand = players[1].hand.filter(tile => tile.id !== southDiscard.id)
players[1].discards.push(southDiscard); players[1].discardKinds![southDiscard.id] = 'TEDASHI'
lastDiscard = { tile: southDiscard, fromSeat: 1, claimed: false, kind: 'TEDASHI' }
snapshot('DISCARD', 1, '南家手切白，其他玩家未鸣牌，轮到西家摸牌。', 'NEED_DRAW', 2)
players[2].hand.push(winningDraw); players[2].drawnTileId = winningDraw.id; wallCount--
snapshot('DRAW', 2, '西家摸入 1 筒：独立摸牌槽显示这张实体牌。', 'NEED_DISCARD', 2)
players[2].hand = players[2].hand.filter(tile => tile.id !== winningDraw.id)
players[2].drawnTileId = null; players[2].discards.push(winningDraw); players[2].discardKinds![winningDraw.id] = 'TSUMOGIRI'
lastDiscard = { tile: winningDraw, fromSeat: 2, claimed: false, kind: 'TSUMOGIRI' }
snapshot('DISCARD', 2, '西家摸切 1 筒：牌河下方横杠标记摸切，东家可点和。', 'REACTION', 2)
players[2].discards = players[2].discards.filter(tile => tile.id !== winningDraw.id)
players[0].hand.push(winningDraw); players[0].score = 20; players[2].score = 0
lastDiscard = { ...lastDiscard, claimed: true }
const result: Result = {
  draw: false, matchOver: true, title: '样例东家 点和', winnerId: identity.playerId, winningTile: winningDraw,
  rawFan: 6, fan: 6,
  items: [
    { id: 'PINGHE', name: '平和', fan: 1, description: '123 筒、123 筒、456 筒、789 筒，雀头 55 筒。' },
    { id: 'MENQING', name: '门清', fan: 2, description: '赢家没有吃、碰或明杠。' },
    { id: 'QINGYISE', name: '清一色', fan: 3, description: '赢家的所有牌均为筒子，不含字牌。' },
  ],
  payments: [{ fromId: 'sample-p3', toId: identity.playerId, amount: 10, requested: 18 }],
  scores: players.map(player => ({ playerId: player.id, name: player.name, score: player.score,
    delta: player.seat === 0 ? 10 : player.seat === 2 ? -10 : 0, rank: player.seat + 1 })),
  hands: players.map(player => ({ playerId: player.id, hand: copy(player.hand), melds: copy(player.melds) })),
  reason: '本地合成结算：点和 6 番应付 18 点，西家仅余 10 点，支付至 0 点后终场。',
}
snapshot('WIN', 0, '东家点和 1 筒，平和 1 + 门清 2 + 清一色 3 = 6 番。', 'MATCH_END', 0, result)
const completedAt = frames.at(-1)!.timestamp
const record: HandRecord = { roomId: 'room1', roomName: '本地合成牌谱', round: 1, roundLabel: '东一局',
  startedAt, completedAt, complete: true, incomplete: false, frames, result }
const listing: ReplayList = { roomId: 'room1', roomName: record.roomName, hands: [{ round: 1, roundLabel: '东一局',
  startedAt, completedAt, frameCount: frames.length, incomplete: false, title: '合成关键帧 · 东家 6 番点和' }],
  note: '仅本地开发验收：8 个合成关键帧，无真实身份、无服务器读写。' }

const previousAdapter = yaomingApi.defaults.adapter
const readonlyAdapter: AxiosAdapter = async config => {
  if ((config.method || 'get').toLowerCase() !== 'get')
    throw new AxiosError('本地只读验收禁止写入请求', 'ERR_NOT_SUPPORT', config)
  const data = config.url === '/replays' ? listing : config.url === '/replays/room1/1' ? record : null
  if (!data) throw new AxiosError('本地验收仅允许两个合成牌谱查询，不连接服务器', 'ERR_NOT_SUPPORT', config)
  readCount.value++
  return { data: copy(data), status: 200, statusText: 'OK', headers: {}, config }
}
// Installed only by this dev entry; every other method/path fails closed without a network fallback.
yaomingApi.defaults.adapter = readonlyAdapter
onUnmounted(() => { if (yaomingApi.defaults.adapter === readonlyAdapter) yaomingApi.defaults.adapter = previousAdapter })
</script>

<template>
  <div class="ym-app replay-layout-preview">
    <main class="ym-main">
      <header class="replay-preview-banner" aria-label="本地只读验收说明">
        <strong>牌谱布局 · 本地只读验收</strong>
        <p>这是合成样例，不是真实牌局。不读取本机座位身份、不访问服务器；所有非 GET 请求均拒绝。</p>
        <p role="status">{{ notice }} 本地查询：{{ readCount }} 次。</p>
      </header>
      <ReplayPage :identity="identity" :room="null" :history="history"
        @close="notice = '这是独立验收页面，没有真实大厅；可以继续测试牌谱。'"
        @forget="notice = '合成身份仅在内存中，没有本机历史可删除。'" />
      <p class="replay-preview-footer">验收页结束 · 到达首末步时可继续正常滚动页面。</p>
    </main>
  </div>
</template>

<style scoped>
.replay-preview-banner { margin-bottom: 16px; padding: 12px 16px; border: 1px solid #a6b997; border-radius: 12px; background: #ecf1e5; color: #355a43; }
.replay-preview-banner p { margin: 6px 0 0; font-size: 12px; line-height: 1.5; }
.replay-preview-footer { margin: 18px 0; padding-top: 10px; border-top: 1px dashed #a6b997; font-size: 12px; color: #66765b; }
</style>

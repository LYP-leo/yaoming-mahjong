import type { Player, Result, RoomView } from './types'
export const tile = { id: 't1', suit: 'BAMBOO', rank: 1, label: '一条' }
export function player(id = 'p1', seat = 0): Player {
  return { id, name: `玩家${seat + 1}`, seat, wind: ['东', '南', '西'][seat], score: 10, bot: false, ready: false, online: true, acknowledged: false, trustee: false, hand: id === 'p1' ? [tile] : [], handSize: 13, discards: [], melds: [], discardedCodes: [], passedCodes: [] }
}
export function room(overrides: Partial<RoomView> = {}): RoomView {
  return { id: 'room1', name: '测试牌桌', version: 1, status: 'NEED_DISCARD', round: 1, roundLabel: '东一局', dealerSeat: 0, currentSeat: 0, wallCount: 68, message: '请出牌', meId: 'p1', players: [player(), player('p2', 1), player('p3', 2)], actions: [{ type: 'DISCARD', label: '出牌', tileIds: ['t1'] }, { type: 'LEAVE', label: '离开', tileIds: [] }], result: null, events: [], dice: null, deadlineAt: null, winHint: '不足 4 番', clientReceivedAt: Date.now(), ...overrides }
}
export function result(overrides: Partial<Result> = {}): Result {
  return { draw: false, matchOver: false, title: '自摸', winnerId: 'p1', winningTile: tile, rawFan: 4, fan: 4, items: [{ id: 'QINGSILIAN', name: '清四连', fan: 4, description: '仅有连续四种数值，不含字牌；159 万支持特殊连续。' }], payments: [{ fromId: 'p2', toId: 'p1', amount: 4, requested: 4 }], scores: [{ playerId: 'p1', name: '甲', score: 18, delta: 8, rank: 1 }, { playerId: 'p2', name: '乙', score: 6, delta: -4, rank: 2 }, { playerId: 'p3', name: '丙', score: 6, delta: -4, rank: 2 }], hands: [{ playerId: 'p1', hand: [tile], melds: [] }], reason: '本局结束', ...overrides }
}

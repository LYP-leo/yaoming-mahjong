import type { DiscardKind, HandRecord, Meld, Result, Tile } from './types'
import { capacityOf, ruleIdOf, ruleNameOf } from './ruleProfiles'
const discardKind = (kind: unknown): DiscardKind | null => kind === 'TSUMOGIRI' || kind === 'TEDASHI' ? kind : null
const tile = (t: Tile) => ({ id: t.id, suit: t.suit, rank: t.rank, label: t.label, ...(t.red ? { red: true } : {}) })
const meld = (m: Meld) => ({ type: m.type, tiles: m.tiles.map(tile), fromSeat: m.fromSeat, claimedTileId: m.claimedTileId, concealed: m.concealed, added: !!m.added })
const result = (r: Result | null) => r ? {
  draw: r.draw, matchOver: r.matchOver, title: r.title, winnerId: r.winnerId, winningTile: r.winningTile ? tile(r.winningTile) : null,
  rawFan: r.rawFan, fan: r.fan, reason: r.reason,
  items: r.items.map(f => ({ id: f.id, name: f.name, fan: f.fan, description: f.description })),
  payments: r.payments.map(p => ({ fromId: p.fromId, toId: p.toId, amount: p.amount, requested: p.requested })),
  scores: r.scores.map(s => ({ playerId: s.playerId, name: s.name, score: s.score, delta: s.delta, rank: s.rank })),
  hands: r.hands.map(h => ({ playerId: h.playerId, hand: h.hand.map(tile), melds: h.melds.map(meld) })),
} : null
/** Explicit public schema: identity/token, wall order, and future private fields never spread into exports. */
export function replayJson(record: HandRecord): string {
  if (!record.complete) throw new Error('只能导出已结束小局')
  return JSON.stringify({ format: 'yaoming-replay-v1', roomId: record.roomId, roomName: record.roomName,
    ruleId: ruleIdOf(record), ruleName: ruleNameOf(record), capacity: capacityOf(record), round: record.round, roundLabel: record.roundLabel,
    startedAt: record.startedAt, completedAt: record.completedAt, complete: record.complete, incomplete: record.incomplete,
    frames: record.frames.map(f => ({ index: f.index, timestamp: f.timestamp, type: f.type, actorSeat: f.actorSeat, message: f.message, status: f.status,
      currentSeat: f.currentSeat, wallCount: f.wallCount, dealerSeat: f.dealerSeat,
      players: f.players.map(p => ({ id: p.id, name: p.name, seat: p.seat, score: p.score, hand: p.hand.map(tile), discards: p.discards.map(tile),
        discardKinds: p.discardKinds == null ? null : Object.fromEntries(p.discards.flatMap(t => { const kind = discardKind(p.discardKinds?.[t.id]); return kind ? [[t.id, kind]] : [] })),
        melds: p.melds.map(meld), drawnTileId: p.drawnTileId ?? null })),
      lastDiscard: f.lastDiscard ? { tile: tile(f.lastDiscard.tile), fromSeat: f.lastDiscard.fromSeat, claimed: f.lastDiscard.claimed, kind: discardKind(f.lastDiscard.kind) } : null,
      dice: f.dice ? { opening: [...f.dice.opening], breaking: [...f.dice.breaking], openingSeat: f.dice.openingSeat, breakStack: f.dice.breakStack } : null,
      result: result(f.result),
    })), result: result(record.result),
  }, null, 2)
}
export function downloadReplay(record: HandRecord) {
  const url = URL.createObjectURL(new Blob([replayJson(record)], { type: 'application/json;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = `yaoming-${record.roomId.replace(/[^a-zA-Z0-9_-]/g, '_')}-${record.round}.json`
  document.body.appendChild(anchor); anchor.click(); anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

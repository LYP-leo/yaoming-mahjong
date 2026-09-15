import type { Identity, ReplayIdentity } from './types'
const KEY = 'yaoming.replayHistory.v1'
export function readReplayHistory(): ReplayIdentity[] {
  try {
    const data: unknown = JSON.parse(localStorage.getItem(KEY) || '[]')
    if (!Array.isArray(data)) return []
    return data.filter((entry): entry is ReplayIdentity => !!entry && ['roomId', 'playerId', 'token'].every(key => typeof entry[key] === 'string' && entry[key].length > 0))
      .slice(0, 20).map(entry => ({ roomId: entry.roomId, playerId: entry.playerId, token: entry.token, roomName: typeof entry.roomName === 'string' ? entry.roomName : entry.roomId, playerName: typeof entry.playerName === 'string' ? entry.playerName : '我的座位', savedAt: Number(entry.savedAt) || 0 }))
  } catch { return [] }
}
export function rememberReplayIdentity(identity: Identity, roomName = '', playerName = ''): ReplayIdentity[] {
  const previous = readReplayHistory()
  const old = previous.find(entry => entry.roomId === identity.roomId && entry.playerId === identity.playerId)
  const entry = { ...identity, roomName: roomName || old?.roomName || identity.roomId, playerName: playerName || old?.playerName || '我的座位', savedAt: Date.now() }
  const next = [entry, ...previous.filter(item => item.roomId !== identity.roomId || item.playerId !== identity.playerId)].slice(0, 20)
  try { localStorage.setItem(KEY, JSON.stringify(next)) } catch { /* Full/private storage does not prevent play. */ }
  return next
}
export function removeReplayIdentity(identity: Pick<Identity, 'roomId' | 'playerId'>): ReplayIdentity[] {
  const next = readReplayHistory().filter(item => item.roomId !== identity.roomId || item.playerId !== identity.playerId)
  try { localStorage.setItem(KEY, JSON.stringify(next)) } catch { /* Best-effort local preference. */ }
  return next
}

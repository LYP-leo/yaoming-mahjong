import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { Action, Identity, RoomView } from './types'

export const AUTO_DRAW_DELAY_MS = 250
export interface AutoDrawContext {
  room: RoomView | null; identity: Identity | null; enabled: boolean; paused: boolean;
  connected: boolean; busy: boolean; syncing: boolean;
}

/** The client requests only DRAW, using the same guarded/idempotent action path as a click. */
export function useAutoDraw(getContext: () => AutoDrawContext, send: (action: Action) => Promise<boolean>) {
  const mounted = ref(false), failedKey = ref('')
  const attempted = new Set<string>()
  let timer: ReturnType<typeof setTimeout> | null = null
  let generation = 0
  const current = computed(() => {
    const context = getContext(), room = context.room, identity = context.identity
    const me = room?.players.find(player => player.id === room.meId)
    const action = room?.actions.find(action => action.type === 'DRAW' && action.tileIds.length === 0)
    const ownTurn = !!room && !!identity && !!me && identity.roomId === room.id && identity.playerId === room.meId
      && room.status === 'NEED_DRAW' && room.currentSeat === me.seat && !me.trustee && !me.bot && !!action
    // Version/serverTime are deliberately not a turn ID: polls, reconnects and failed requests
    // may increase a version without changing which physical draw is being requested.
    const key = ownTurn ? JSON.stringify([identity!.roomId, identity!.playerId, identity!.token, room!.round,
      room!.currentSeat, room!.wallCount, me!.hand.map(tile => tile.id).sort(), me!.discards.map(tile => tile.id),
      me!.melds.map(meld => [meld.type, meld.added, meld.tiles.map(tile => tile.id)])]) : ''
    return { key, version: room?.version, action, eligible: ownTurn && mounted.value && context.enabled && !context.paused && context.connected && !context.busy && !context.syncing }
  })
  function cancel() { generation++; if (timer) clearTimeout(timer); timer = null }
  function expired(room: RoomView) {
    if (!room.deadlineAt) return false
    const end = new Date(room.deadlineAt).getTime(), serverTime = Date.parse(room.serverTime || '')
    const offset = Number.isFinite(serverTime) && typeof room.clientReceivedAt === 'number' ? serverTime - room.clientReceivedAt : 0
    return Number.isFinite(end) && end <= Date.now() + offset
  }
  watch([() => current.value.key, () => current.value.version, () => current.value.eligible], () => {
    cancel()
    const scheduled = current.value
    if (!scheduled.eligible || !scheduled.key || attempted.has(scheduled.key)) return
    const expected = generation
    timer = setTimeout(async () => {
      timer = null
      const latest = current.value, room = getContext().room
      if (expected !== generation || !latest.eligible || latest.key !== scheduled.key || latest.version !== scheduled.version || !latest.action || !room || expired(room)) return
      attempted.add(scheduled.key)
      if (attempted.size > 256) attempted.delete(attempted.values().next().value!)
      failedKey.value = ''
      let accepted = false
      try { accepted = await send(latest.action) } catch { /* The normal action layer reports the underlying request error. */ }
      if (mounted.value && current.value.key === scheduled.key && !accepted) failedKey.value = scheduled.key
    }, AUTO_DRAW_DELAY_MS)
  }, { immediate: true, flush: 'sync' })
  onMounted(() => { mounted.value = true })
  onUnmounted(() => { mounted.value = false; cancel() })
  const notice = computed(() => failedKey.value && failedKey.value === current.value.key ? '自动摸牌未成功，请点击「摸牌」手动重试。' : '')
  return { notice }
}

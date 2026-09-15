import { computed, onUnmounted, ref, watch } from 'vue'
import type { RoomView } from './types'

export function useServerDeadline(getRoom: () => RoomView) {
  const tick = ref(Date.now())
  const offset = computed(() => {
    const { serverTime, clientReceivedAt } = getRoom()
    const timestamp = serverTime ? Date.parse(serverTime) : NaN
    return Number.isFinite(timestamp) && typeof clientReceivedAt === 'number' && Number.isFinite(clientReceivedAt) ? timestamp - clientReceivedAt : 0
  })
  watch(() => [getRoom().serverTime, getRoom().clientReceivedAt], () => {
    tick.value = Date.now()
  }, { immediate: true })
  const timer = setInterval(() => { tick.value = Date.now() }, 250)
  onUnmounted(() => clearInterval(timer))
  const remaining = computed(() => {
    const raw = getRoom().deadlineAt
    if (!raw) return null
    const end = new Date(raw).getTime()
    return Number.isFinite(end) ? Math.max(0, Math.ceil((end - tick.value - offset.value) / 1000)) : null
  })
  const kindLabel = computed(() => ({ DRAW: '摸牌', DISCARD: '出牌', REACTION: '响应', SETTLEMENT: '确认结算' })[getRoom().deadlineKind || 'REACTION'])
  const expired = computed(() => remaining.value === 0 && !!getRoom().deadlineKind)
  return { remaining, kindLabel, expired }
}

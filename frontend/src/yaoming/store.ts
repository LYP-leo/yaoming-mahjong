import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import axios from 'axios'
import type { Action, Identity, RoomSummary, RoomView, Rules, RuleId } from './types'
import { isRuleId, ruleIdOf } from './ruleProfiles'
import { openRoomStream } from './roomStream'
import { readReplayHistory, rememberReplayIdentity, removeReplayIdentity } from './replayHistory'

export const yaomingApi = axios.create({ baseURL: '/api/yaoming', timeout: 15000 })
const STORAGE_KEY = 'yaoming.identity.v1'
const sameIdentity = (first: Identity | null, second: Identity | null) => !!first && !!second
  && first.roomId === second.roomId && first.playerId === second.playerId && first.token === second.token

export function createRequestId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('')
}

function readIdentity(): Identity | null {
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? localStorage.getItem(STORAGE_KEY) ?? 'null')
    if (!saved?.roomId || !saved?.playerId || !saved?.token) return null
    // Pin recovered identity to this tab before another tab can replace shared recovery.
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(saved))
    return saved
  } catch { return null }
}
function errorText(error: unknown): string {
  const e = error as { response?: { status?: number; data?: { message?: string; error?: string } | string }; message?: string; code?: string }
  const data = e.response?.data
  const detail = typeof data === 'string' ? data : data?.message || data?.error
  if (detail && /[\u3400-\u9fff]/.test(detail)) return detail
  const status = e.response?.status
  if (status && status >= 500) return '服务暂时不可用，正在重试连接'
  if (status === 404) return '房间不存在或已经结束'
  if (status === 401 || status === 403) return '无法验证玩家身份，请重新连接或使用恢复码'
  if (status === 409) return '牌桌已更新，请同步后重新操作'
  if (status) return `请求未被接受（${status}），请同步后重试`
  if (e.code === 'ECONNABORTED' || /timeout/i.test(e.message || '')) return '连接超时，正在重试连接'
  if (e.code === 'ERR_NETWORK' || /Network Error|Failed to fetch/i.test(e.message || '')) return '网络连接中断，正在重试连接'
  if (e.message && /[\u3400-\u9fff]/.test(e.message)) return e.message
  return '连接失败，请重试同步'
}

export const useYaomingStore = defineStore('yaoming', () => {
  const identity = ref<Identity | null>(readIdentity())
  const room = ref<RoomView | null>(null)
  const rooms = ref<RoomSummary[]>([])
  const rules = ref<Rules | null>(null)
  const rulesets = ref<Rules[]>([])
  const rulesById = new Map<RuleId, Rules>()
  let rulesGeneration = 0
  const error = ref('')
  const busy = ref(false)
  const syncing = ref(false)
  const lastSync = ref(0)
  const connected = ref(false)
  const recoveryRequired = ref(false)
  const detachedIdentity = ref<Identity | null>(null)
  const detachedRecoveryRequired = ref(false)
  const replayHistory = ref(readReplayHistory())
  const pushHealthy = ref(false)
  const me = computed(() => room.value?.players.find(p => p.id === room.value?.meId))
  let epoch = 0
  let timer: ReturnType<typeof setTimeout> | null = null
  let polling = false
  let pollingGeneration = 0
  let lastSyncError = ''
  let closeStream: (() => void) | null = null
  let streamGeneration = 0
  let pushDirty = false
  const identityKey = () => identity.value ? JSON.stringify([identity.value.roomId, identity.value.playerId, identity.value.token]) : ''

  function rememberSeat() {
    if (identity.value) replayHistory.value = rememberReplayIdentity(identity.value, room.value?.id === identity.value.roomId ? room.value.name : '', me.value?.name)
  }
  function removeHistory(seat: Pick<Identity, 'roomId' | 'playerId'>) { replayHistory.value = removeReplayIdentity(seat) }
  function flushPush() {
    if (pushDirty && polling && identity.value && !busy.value && !syncing.value) { pushDirty = false; void refresh() }
  }
  function restartStream() {
    streamGeneration++
    closeStream?.(); closeStream = null; pushDirty = false; pushHealthy.value = false
    if (polling) schedule(pollingGeneration)
    if (!polling || !identity.value || recoveryRequired.value) return
    const expected = streamGeneration, key = identityKey()
    closeStream = openRoomStream({ ...identity.value }, {
      // jsdom/non-browser stores have no window.fetch; tests opt in with a mock.
      fetcher: typeof window.fetch === 'function' ? window.fetch.bind(window) : undefined,
      onHealth: healthy => {
        if (expected !== streamGeneration) return
        const changed = pushHealthy.value !== healthy
        pushHealthy.value = healthy
        if (changed && polling) schedule(pollingGeneration)
      },
      onNotice: notice => {
        if (expected !== streamGeneration || key !== identityKey()) return
        if (notice.type === 'CLOSED' || !room.value || notice.version > room.value.version) { pushDirty = true; flushPush() }
      },
    })
  }
  watch([identityKey, () => recoveryRequired.value], () => { restartStream() }, { flush: 'sync' })

  function reportSyncError(cause: unknown) {
    const message = errorText(cause)
    // Keep an explicit action failure visible even if a background read also fails.
    if (!error.value || error.value === lastSyncError) error.value = message
    lastSyncError = message
  }
  function clearSyncError() {
    if (lastSyncError && error.value === lastSyncError) error.value = ''
    lastSyncError = ''
  }

  function saveIdentity(next: Identity | null) {
    const previous = identity.value
    if (previous) rememberSeat()
    identity.value = next
    recoveryRequired.value = false
    epoch++
    if (next) {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next))
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
      rememberSeat()
      if (sameIdentity(detachedIdentity.value, next)) { detachedIdentity.value = null; detachedRecoveryRequired.value = false }
    } else {
      sessionStorage.setItem(STORAGE_KEY, 'null')
      if (localStorage.getItem(STORAGE_KEY) === JSON.stringify(previous)) localStorage.removeItem(STORAGE_KEY)
    }
  }
  function accept(view: RoomView, expected: number) {
    if (expected !== epoch || view.id !== identity.value?.roomId) return
    ruleIdOf(view)
    const current = room.value
    const incomingTime = Date.parse(view.serverTime || '')
    const currentTime = Date.parse(current?.serverTime || '')
    const staleVersion = current?.id === view.id && view.version < current.version
    const staleClock = current?.id === view.id && view.version === current.version && Number.isFinite(currentTime) && (!Number.isFinite(incomingTime) || incomingTime <= currentTime)
    if (!staleVersion && !staleClock) {
      // Overwrite any similarly named server payload field at the network boundary.
      room.value = { ...view, clientReceivedAt: Date.now() }
      const saved = replayHistory.value.find(entry => entry.roomId === view.id && entry.playerId === identity.value?.playerId)
      if (!saved || saved.roomName !== view.name || saved.playerName !== me.value?.name) rememberSeat()
    }
    connected.value = true
    clearSyncError()
    recoveryRequired.value = false
    lastSync.value = Date.now()
  }
  async function refresh(force = false) {
    if (!identity.value || (syncing.value && !force) || (busy.value && !force)) return
    const current = { ...identity.value }
    const expected = epoch
    syncing.value = true
    try {
      const response = await yaomingApi.get<RoomView>(`/rooms/${encodeURIComponent(current.roomId)}`, {
        params: { playerId: current.playerId }, headers: { 'X-Resume-Token': current.token },
      })
      accept(response.data, expected)
    } catch (e) {
      if (expected === epoch) {
        connected.value = false; reportSyncError(e)
        const status = (e as { response?: { status?: number } }).response?.status
        if (status === 404 || status === 401 || (status === 400 || status === 403) && /身份无效|恢复码无效|玩家已退出/.test(errorText(e))) {
          recoveryRequired.value = true
          room.value = null
        }
      }
    } finally { syncing.value = false; flushPush() }
  }
  async function loadLobby() {
    try {
      const response = await yaomingApi.get<RoomSummary[]>('/rooms')
      response.data.forEach(ruleIdOf)
      rooms.value = response.data
      connected.value = true
      clearSyncError()
    } catch (e) { connected.value = false; reportSyncError(e) }
  }
  async function loadRules(ruleId: RuleId = 'yaoming-3p') {
    const expected = ++rulesGeneration
    if (rules.value && (rules.value.id || 'yaoming-3p') !== ruleId) rules.value = rulesById.get(ruleId) || null
    try {
      const response = ruleId === 'yaoming-3p' ? await yaomingApi.get<Rules>('/rules')
        : await yaomingApi.get<Rules>('/rules', { params: { ruleId } })
      if ((response.data.id || 'yaoming-3p') !== ruleId) throw new Error('规则详情与所选规则不一致')
      if (expected !== rulesGeneration) return
      rulesById.set(ruleId, response.data); rules.value = response.data
    } catch (e) { if (expected === rulesGeneration) error.value = errorText(e) }
  }
  async function loadRulesets() {
    try {
      const { data } = await yaomingApi.get<Rules[]>('/rulesets')
      if (Array.isArray(data)) rulesets.value = data.filter(rule => isRuleId(rule.id))
    } catch { /* Existing named choices remain usable if catalog discovery is temporarily unavailable. */ }
  }
  async function enter(path: string, payload: object) {
    if (busy.value) return false
    busy.value = true; error.value = ''
    try {
      const response = await yaomingApi.post<Identity>(path, payload)
      saveIdentity(response.data)
      room.value = null
      await refresh(true)
      return !!room.value
    } catch (e) { error.value = errorText(e); return false }
    finally { busy.value = false; flushPush() }
  }
  const create = (name: string, playerName: string, ruleId?: RuleId) => enter('/rooms', { name, playerName, ...(ruleId ? { ruleId } : {}) })
  const join = (id: string, playerName: string) => enter(`/rooms/${encodeURIComponent(id.trim())}/join`, { playerName })
  const resume = (id: string, token: string) => enter(`/rooms/${encodeURIComponent(id.trim())}/resume`, { token: token.trim() })

  async function act(action: Action): Promise<boolean> {
    if (busy.value || !room.value || !identity.value) return false
    const allowed = room.value.actions.some(a => a.type === action.type && JSON.stringify(a.tileIds) === JSON.stringify(action.tileIds))
    if (!allowed) { error.value = '这个动作已经不可用，正在同步牌局'; await refresh(); return false }
    busy.value = true; error.value = ''; epoch++
    const expected = epoch
    const current = { ...identity.value }
    const desiredTrustee = action.type === 'TRUSTEE' ? !me.value?.trustee : null
    const sameSeat = () => expected === epoch && identity.value?.roomId === current.roomId && identity.value?.playerId === current.playerId
      && identity.value?.token === current.token && room.value?.id === current.roomId && room.value?.meId === current.playerId
    try {
      // Trustee intent and explicit departure survive stale versions; tile actions never do.
      const retriesVersionConflict = desiredTrustee !== null || action.type === 'LEAVE'
      const maximumAttempts = retriesVersionConflict ? 3 : 1
      for (let attempt = 0; attempt < maximumAttempts; attempt++) {
        if (!sameSeat()) return false
        const body = {
          playerId: current.playerId, token: current.token, version: room.value!.version,
          requestId: createRequestId(), type: action.type, tileIds: action.tileIds,
        }
        const write = () => yaomingApi.post<RoomView | null>(`/rooms/${encodeURIComponent(current.roomId)}/actions`, body, { headers: { 'X-Resume-Token': current.token } })
        let response
        try {
          try { response = await write() }
          catch (e) {
            if ((e as { response?: unknown }).response) throw e
            if (!sameSeat()) return false
            // A lost response may still have committed: retry the identical request ID.
            response = await write()
          }
        } catch (e) {
          if (!retriesVersionConflict || (e as { response?: { status?: number } }).response?.status !== 409) throw e
          if (!sameSeat()) return false
          await refresh(true)
          if (!sameSeat() || !connected.value || recoveryRequired.value) return false
          if (desiredTrustee !== null && me.value?.trustee === desiredTrustee) { error.value = ''; return true }
          if (attempt + 1 < maximumAttempts && room.value!.actions.some(a => a.type === action.type && a.tileIds.length === 0)) continue
          error.value = action.type === 'LEAVE' ? '牌局更新较快，尚未退出房间，请再试一次' : '牌局更新较快，控制状态尚未切换，请再试一次'
          return false
        }
        const { data } = response
        if (!sameSeat()) return false
        if (action.type === 'LEAVE') {
          if (sameIdentity(detachedIdentity.value, current)) { detachedIdentity.value = null; detachedRecoveryRequired.value = false }
          saveIdentity(null); room.value = null
          await loadLobby()
        } else if (data) accept(data, expected)
        return true
      }
      return false
    } catch (e) {
      if (!sameSeat()) return false
      error.value = errorText(e)
      await refresh(true)
      return false
    } finally { busy.value = false; flushPush() }
  }
  // A detached seat is not the active session. Never resume it or install its
  // snapshot just to leave: doing so could replace another tab's recovery data.
  async function leaveDetached(): Promise<boolean> {
    if (busy.value || identity.value || !detachedIdentity.value) return false
    const current = { ...detachedIdentity.value }, expected = epoch
    const stillDetached = () => expected === epoch && !identity.value && sameIdentity(detachedIdentity.value, current)
    busy.value = true; error.value = ''; detachedRecoveryRequired.value = false
    try {
      for (let attempt = 0; attempt < 3; attempt++) {
        const { data: view } = await yaomingApi.get<RoomView>(`/rooms/${encodeURIComponent(current.roomId)}`, {
          params: { playerId: current.playerId }, headers: { 'X-Resume-Token': current.token },
        })
        if (!stillDetached()) return false
        if (view.id !== current.roomId || view.meId !== current.playerId) throw new Error('原房间身份与返回的牌桌不一致，尚未退出')
        ruleIdOf(view)
        if (!view.actions.some(action => action.type === 'LEAVE' && action.tileIds.length === 0)) throw new Error('当前不能退出原房间，请稍后重试')
        const body = { playerId: current.playerId, token: current.token, version: view.version,
          requestId: createRequestId(), type: 'LEAVE', tileIds: [] }
        const write = () => yaomingApi.post<RoomView | null>(`/rooms/${encodeURIComponent(current.roomId)}/actions`, body,
          { headers: { 'X-Resume-Token': current.token } })
        try {
          try { await write() }
          catch (e) {
            if ((e as { response?: unknown }).response) throw e
            if (!stillDetached()) return false
            // The first request may already have committed. Retry its exact ID.
            await write()
          }
        } catch (e) {
          if (!stillDetached()) return false
          if ((e as { response?: { status?: number } }).response?.status !== 409) throw e
          if (attempt < 2) continue
          throw new Error('牌局更新较快，尚未退出原房间，请再试一次')
        }
        if (!stillDetached()) return false
        clearDetached(current)
        await loadLobby()
        return true
      }
      return false
    } catch (e) {
      if (!stillDetached()) return false
      error.value = errorText(e)
      const status = (e as { response?: { status?: number } }).response?.status
      detachedRecoveryRequired.value = status === 404 || status === 401
        || (status === 400 || status === 403) && /身份无效|恢复码无效|玩家已退出/.test(error.value)
      return false
    } finally { busy.value = false; flushPush() }
  }
  function clearDetached(current: Identity) {
    if (!sameIdentity(detachedIdentity.value, current)) return
    detachedIdentity.value = null; detachedRecoveryRequired.value = false
    if (localStorage.getItem(STORAGE_KEY) === JSON.stringify(current)) localStorage.removeItem(STORAGE_KEY)
  }
  function forgetDetached() {
    if (busy.value || identity.value || !detachedRecoveryRequired.value || !detachedIdentity.value) return
    clearDetached(detachedIdentity.value); error.value = ''
  }
  async function start() {
    const generation = ++pollingGeneration
    polling = true
    restartStream()
    await Promise.all([loadRules(), loadRulesets(), identity.value ? refresh() : loadLobby()])
    if (generation === pollingGeneration) schedule(generation)
  }
  function schedule(generation: number) {
    if (!polling || generation !== pollingGeneration) return
    if (timer) clearTimeout(timer)
    timer = setTimeout(async () => {
      if (identity.value) await refresh()
      else await loadLobby()
      schedule(generation)
    }, identity.value && pushHealthy.value ? 10000 : 2000)
  }
  function stop() { polling = false; pollingGeneration++; if (timer) clearTimeout(timer); timer = null; streamGeneration++; closeStream?.(); closeStream = null; pushHealthy.value = false; pushDirty = false }
  function forget() {
    saveIdentity(null); room.value = null; error.value = ''
    void loadLobby()
  }
  function detach() {
    if (busy.value) return
    rememberSeat()
    detachedIdentity.value = identity.value ? { ...identity.value } : null
    detachedRecoveryRequired.value = false
    identity.value = null; room.value = null; recoveryRequired.value = false; error.value = ''; epoch++
    // Explicit tab-local null suppresses shared fallback while keeping recovery for other tabs.
    sessionStorage.setItem(STORAGE_KEY, 'null')
    void loadLobby()
  }
  async function retrySync() {
    error.value = ''
    await Promise.all([loadRules(), identity.value ? refresh() : loadLobby()])
  }
  return { identity, room, rooms, rules, rulesets, loadRulesets, error, busy, syncing, connected, lastSync, recoveryRequired, detachedIdentity, detachedRecoveryRequired, me, replayHistory, pushHealthy,
    create, join, resume, act, refresh, loadRules, loadLobby, retrySync, start, stop, forget, detach, leaveDetached, forgetDetached, removeHistory }
})

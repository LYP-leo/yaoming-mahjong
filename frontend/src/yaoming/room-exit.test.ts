import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import YaomingApp from './YaomingApp.vue'
import { useYaomingStore, yaomingApi } from './store'
import { room } from './testFixtures'
import type { Identity, RoomView } from './types'

const own: Identity = { roomId: 'room1', playerId: 'p1', token: 'exit-test-only-private' }
const other: Identity = { roomId: 'room2', playerId: 'p2', token: 'other-test-only-private' }
const storageKey = 'yaoming.identity.v1'
const wrappers: VueWrapper[] = []
const keep = <T extends VueWrapper>(wrapper: T) => { wrappers.push(wrapper); return wrapper }
const requestFailure = (status: number, message = '') => ({ response: { status, data: { message } } })
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(accept => { resolve = accept }); return { promise, resolve } }
function detached() {
  const store = useYaomingStore(); store.detachedIdentity = { ...own }
  sessionStorage.setItem(storageKey, 'null'); localStorage.setItem(storageKey, JSON.stringify(own))
  return store
}
function app(value: RoomView | null = room({ status: 'WAITING' })) {
  const store = useYaomingStore(); store.identity = value ? { ...own } : null; store.room = value
  vi.spyOn(store, 'start').mockResolvedValue(); vi.spyOn(store, 'stop').mockImplementation(() => {})
  return { store, wrapper: keep(mount(YaomingApp, { attachTo: document.body })) }
}
const button = (wrapper: VueWrapper, text: string) => wrapper.findAll('button').find(node => node.text() === text)!
beforeEach(() => { vi.restoreAllMocks(); localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia()) })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.restoreAllMocks() })

describe('explicit exit presentation', () => {
  it('exits a waiting seat directly and shows no misleading detach choice or confirmation', async () => {
    const { store, wrapper } = app(), act = vi.spyOn(store, 'act').mockResolvedValue(true)
    const detach = vi.spyOn(store, 'detach')
    expect(wrapper.get('.ym-room-heading').text()).not.toContain('暂离')
    await button(wrapper, '退出房间 · 返回大厅').trigger('click')
    expect(act).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ type: 'LEAVE' }))
    expect(detach).not.toHaveBeenCalled(); expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('keeps a waiting seat and visible error after a failed exit; disables repeat clicks while busy', async () => {
    const { store, wrapper } = app(), act = vi.spyOn(store, 'act').mockImplementation(async () => { store.error = '退出未成功，请重试'; return false })
    await button(wrapper, '退出房间 · 返回大厅').trigger('click'); await flushPromises()
    expect(store.room?.status).toBe('WAITING'); expect(wrapper.get('[role="alert"]').text()).toContain('退出未成功')
    store.busy = true; await flushPromises()
    expect(button(wrapper, '退出房间 · 返回大厅').attributes('disabled')).toBeDefined()
    await button(wrapper, '退出房间 · 返回大厅').trigger('click'); expect(act).toHaveBeenCalledTimes(1)
  })

  it('keeps the active-game leave confirmation open on failure and closes only on success', async () => {
    const { store, wrapper } = app(room()), act = vi.spyOn(store, 'act').mockImplementation(async () => { store.error = '网络中断，尚未退出'; return false })
    await button(wrapper, '离开房间').trigger('click'); expect(act).not.toHaveBeenCalled()
    await button(wrapper, '确认离开').trigger('click'); await flushPromises()
    expect(wrapper.get('[aria-labelledby="ym-leave-title"]').text()).toContain('尚未退出')
    store.busy = true; await flushPromises()
    expect(button(wrapper, '确认离开').attributes('disabled')).toBeDefined()
    expect(button(wrapper, '留在牌桌').attributes('disabled')).toBeDefined()
    store.busy = false; act.mockResolvedValue(true); await flushPromises()
    await button(wrapper, '确认离开').trigger('click'); await flushPromises()
    expect(wrapper.find('[aria-labelledby="ym-leave-title"]').exists()).toBe(false)
  })

  it('offers detached exit explicitly and explains default expiry instead of promising a permanent seat', async () => {
    const { store, wrapper } = app(null); store.detachedIdentity = { ...own }; await flushPromises()
    const leave = vi.spyOn(store, 'leaveDetached').mockImplementation(async () => { store.error = '房间已清理'; store.detachedRecoveryRequired = true; return false })
    expect(wrapper.get('.ym-detached-banner').text()).toContain('尚未退出原房间')
    expect(wrapper.get('.ym-detached-banner').text()).toContain('10 分钟')
    expect(wrapper.get('.ym-detached-banner').text()).toContain('默认')
    expect(wrapper.text()).not.toContain('座位仍为你保留')
    await button(wrapper, '退出原房间').trigger('click'); expect(leave).not.toHaveBeenCalled()
    await button(wrapper, '确认退出原房间').trigger('click'); await flushPromises()
    expect(wrapper.get('[aria-labelledby="ym-detached-leave-title"]').text()).toContain('房间已清理')
    expect(store.detachedIdentity).toEqual(own)
    await wrapper.get('[aria-labelledby="ym-detached-leave-title"]').findAll('button').find(node => node.text() === '清除失效记录')!.trigger('click')
    expect(store.detachedIdentity).toBeNull()
  })
})

describe('detached exit without activating another session', () => {
  it('fetches the current private version and sends LEAVE without resuming or overwriting another tab recovery', async () => {
    const store = detached(); localStorage.setItem(storageKey, JSON.stringify(other))
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: room({ version: 7, status: 'WAITING' }) }).mockResolvedValueOnce({ data: [] })
    const post = vi.spyOn(yaomingApi, 'post').mockResolvedValue({ data: null })
    expect(await store.leaveDetached()).toBe(true)
    expect(get).toHaveBeenNthCalledWith(1, '/rooms/room1', { params: { playerId: 'p1' }, headers: { 'X-Resume-Token': own.token } })
    expect(post).toHaveBeenCalledExactlyOnceWith('/rooms/room1/actions', expect.objectContaining({ playerId: own.playerId, token: own.token, version: 7, type: 'LEAVE', tileIds: [] }), { headers: { 'X-Resume-Token': own.token } })
    expect(post.mock.calls[0][1]).not.toHaveProperty('roomId')
    expect(store.identity).toBeNull(); expect(store.room).toBeNull(); expect(store.detachedIdentity).toBeNull()
    expect(localStorage.getItem(storageKey)).toBe(JSON.stringify(other)); expect(sessionStorage.getItem(storageKey)).toBe('null')
  })

  it('clears only matching recovery after a successful exit and retains replay history', async () => {
    const store = detached()
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: room() }).mockResolvedValueOnce({ data: [] })
    vi.spyOn(yaomingApi, 'post').mockResolvedValue({ data: null })
    const history = store.replayHistory
    expect(await store.leaveDetached()).toBe(true)
    expect(localStorage.getItem(storageKey)).toBeNull(); expect(store.replayHistory).toEqual(history)
  })

  it('retries a lost response with the identical idempotency request and body', async () => {
    const store = detached()
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: room() }).mockResolvedValueOnce({ data: [] })
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce(new Error('timeout')).mockResolvedValueOnce({ data: null })
    expect(await store.leaveDetached()).toBe(true); expect(post).toHaveBeenCalledTimes(2)
    expect(post.mock.calls[0]).toEqual(post.mock.calls[1])
  })

  it('fetches a fresh version after conflict and uses a fresh ID for the new accepted version', async () => {
    const store = detached()
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: room({ version: 1 }) }).mockResolvedValueOnce({ data: room({ version: 3 }) }).mockResolvedValueOnce({ data: [] })
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce(requestFailure(409)).mockResolvedValueOnce({ data: null })
    expect(await store.leaveDetached()).toBe(true)
    expect(post.mock.calls.map(call => (call[1] as { version: number }).version)).toEqual([1, 3])
    expect((post.mock.calls[0][1] as { requestId: string }).requestId).not.toBe((post.mock.calls[1][1] as { requestId: string }).requestId)
  })

  it('bounds conflicts to three writes and keeps recovery when all fail', async () => {
    const store = detached()
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: room() })
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValue(requestFailure(409))
    expect(await store.leaveDetached()).toBe(false)
    expect(post).toHaveBeenCalledTimes(3); expect(get).toHaveBeenCalledTimes(3)
    expect(store.detachedIdentity).toEqual(own); expect(store.error).toContain('尚未退出原房间'); expect(store.busy).toBe(false)
  })

  it.each([404, 401, 403])('preserves failed credentials on %s until the user explicitly clears them', async status => {
    const store = detached()
    vi.spyOn(yaomingApi, 'get').mockRejectedValue(requestFailure(status, '房间不存在或玩家身份无效'))
    const post = vi.spyOn(yaomingApi, 'post')
    expect(await store.leaveDetached()).toBe(false); expect(post).not.toHaveBeenCalled()
    expect(store.detachedIdentity).toEqual(own); expect(localStorage.getItem(storageKey)).toBe(JSON.stringify(own))
    expect(store.detachedRecoveryRequired).toBe(true)
    store.forgetDetached(); expect(store.detachedIdentity).toBeNull(); expect(localStorage.getItem(storageKey)).toBeNull()
  })

  it('does not expose permanent clearing after a network failure or silently erase its seat', async () => {
    const store = detached()
    vi.spyOn(yaomingApi, 'get').mockRejectedValue(new Error('Network Error'))
    expect(await store.leaveDetached()).toBe(false); expect(store.detachedRecoveryRequired).toBe(false)
    store.forgetDetached(); expect(store.detachedIdentity).toEqual(own)
  })

  it.each(['id', 'meId', 'actions'] as const)('rejects a mismatched or unauthorized read result: %s', async change => {
    const store = detached()
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: room(change === 'actions' ? { actions: [] } : { [change]: 'unexpected' }) })
    const post = vi.spyOn(yaomingApi, 'post')
    expect(await store.leaveDetached()).toBe(false); expect(post).not.toHaveBeenCalled(); expect(store.detachedIdentity).toEqual(own)
  })

  it('does not start detached exit while an active identity exists, and serializes double clicks', async () => {
    const store = detached(); store.identity = { ...other }
    const pending = deferred<{ data: RoomView }>(), get = vi.spyOn(yaomingApi, 'get').mockReturnValueOnce(pending.promise as never)
    expect(await store.leaveDetached()).toBe(false); expect(get).not.toHaveBeenCalled()
    store.identity = null
    const first = store.leaveDetached(); expect(await store.leaveDetached()).toBe(false)
    store.detachedIdentity = { ...other }; pending.resolve({ data: room() })
    expect(await first).toBe(false); expect(store.detachedIdentity).toEqual(other)
  })

  it.each(['active', 'detached'] as const)('ignores delayed success after the %s identity changes and never clears the replacement', async change => {
    const store = detached(), pending = deferred<{ data: null }>()
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: room() })
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce(new Error('timeout')).mockReturnValueOnce(pending.promise as never)
    const first = store.leaveDetached(); await flushPromises(); expect(post).toHaveBeenCalledTimes(2)
    expect(post.mock.calls[0]).toEqual(post.mock.calls[1])
    if (change === 'active') store.identity = { ...other }
    else store.detachedIdentity = { ...other }
    localStorage.setItem(storageKey, JSON.stringify(other)); pending.resolve({ data: null })
    expect(await first).toBe(false); expect(localStorage.getItem(storageKey)).toBe(JSON.stringify(other))
    if (change === 'active') expect(store.identity).toEqual(other)
    else expect(store.detachedIdentity).toEqual(other)
  })

  it('removes a matching old detached banner after a successful active LEAVE', async () => {
    const store = detached(); store.identity = { ...own }; store.room = room()
    vi.spyOn(yaomingApi, 'post').mockResolvedValue({ data: null }); vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: [] })
    expect(await store.act(store.room.actions.find(action => action.type === 'LEAVE')!)).toBe(true)
    expect(store.detachedIdentity).toBeNull(); expect(store.identity).toBeNull()
  })
})

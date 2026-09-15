import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRequestId, useYaomingStore, yaomingApi } from './store'
import { room } from './testFixtures'

describe('yaoming private session and serialized actions', () => {
  beforeEach(() => {
    vi.restoreAllMocks(); setActivePinia(createPinia()); localStorage.clear(); sessionStorage.clear()
  })
  function seated() {
    const s = useYaomingStore()
    s.identity = { roomId: 'room1', playerId: 'p1', token: 'private-token' }
    s.room = room()
    return s
  }
  it('restores a saved identity using an authentication header, never a token in the URL', async () => {
    localStorage.setItem('yaoming.identity.v1', JSON.stringify({ roomId: 'room1', playerId: 'p1', token: 'secret' }))
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: room() })
    const s = useYaomingStore(); await s.refresh()
    expect(get).toHaveBeenCalledWith('/rooms/room1', { params: { playerId: 'p1' }, headers: { 'X-Resume-Token': 'secret' } })
    expect(s.room?.id).toBe('room1')
  })
  it('refuses client-invented actions before any write', async () => {
    const post = vi.spyOn(yaomingApi, 'post')
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: room() })
    const s = seated(); expect(await s.act({ type: 'WIN', label: '和', tileIds: [] })).toBe(false)
    expect(post).not.toHaveBeenCalled(); expect(s.error).toContain('不可用')
  })
  it('pins a locally recovered seat to this tab before another tab changes local recovery', () => {
    const own = { roomId: 'room1', playerId: 'p1', token: 'secret-a' }
    localStorage.setItem('yaoming.identity.v1', JSON.stringify(own))
    const first = useYaomingStore()
    expect(first.identity).toEqual(own)
    expect(JSON.parse(sessionStorage.getItem('yaoming.identity.v1')!)).toEqual(own)
    localStorage.setItem('yaoming.identity.v1', JSON.stringify({ roomId: 'room1', playerId: 'p2', token: 'secret-b' }))
    setActivePinia(createPinia())
    expect(useYaomingStore().identity).toEqual(own)
  })
  it('temporarily detaches only this tab without LEAVE or removing another tab recovery', () => {
    const other = JSON.stringify({ roomId: 'room2', playerId: 'p2', token: 'secret-b' })
    localStorage.setItem('yaoming.identity.v1', other)
    const post = vi.spyOn(yaomingApi, 'post')
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: [] })
    const s = seated(); s.detach()
    expect(post).not.toHaveBeenCalled(); expect(s.identity).toBeNull(); expect(s.room).toBeNull()
    expect(s.detachedIdentity?.token).toBe('private-token')
    expect(localStorage.getItem('yaoming.identity.v1')).toBe(other)
    setActivePinia(createPinia())
    expect(useYaomingStore().identity).toBeNull()
  })
  it('does not erase another tab recovery when the current player leaves', async () => {
    const other = JSON.stringify({ roomId: 'room2', playerId: 'p2', token: 'secret-b' })
    localStorage.setItem('yaoming.identity.v1', other)
    vi.spyOn(yaomingApi, 'post').mockResolvedValue({ data: null })
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: [] })
    const s = seated(); await s.act(s.room!.actions[1])
    expect(localStorage.getItem('yaoming.identity.v1')).toBe(other)
    expect(sessionStorage.getItem('yaoming.identity.v1')).toBe('null')
  })
  it.each([404, 401])('shows an actionable recovery state after permanent identity failure %s', async (status) => {
    vi.spyOn(yaomingApi, 'get').mockRejectedValue({ response: { status, data: { message: '房间不存在或身份失效' } } })
    const s = seated(); await s.refresh()
    expect(s.room).toBeNull(); expect(s.identity?.token).toBe('private-token'); expect(s.recoveryRequired).toBe(true)
  })
  it('retries the rule catalog after its initial load failed', async () => {
    const rules = { name: '要命麻将', fans: [], tiles: [], notes: [] }
    const get = vi.spyOn(yaomingApi, 'get').mockRejectedValueOnce(new Error('Network Error'))
    const s = useYaomingStore(); await s.loadRules(); expect(s.rules).toBeNull()
    get.mockResolvedValueOnce({ data: rules }).mockResolvedValueOnce({ data: [] })
    await s.retrySync()
    expect(s.rules?.name).toBe('要命麻将'); expect(s.error).toBe('')
  })
  it('does not let a stale poll overwrite a newer action response', async () => {
    let deliver!: (value: unknown) => void
    vi.spyOn(yaomingApi, 'get').mockReturnValue(new Promise(resolve => { deliver = resolve }) as never)
    vi.spyOn(yaomingApi, 'post').mockResolvedValue({ data: room({ version: 2 }) })
    const s = seated(); const pendingPoll = s.refresh()
    await s.act(s.room!.actions[0])
    deliver({ data: room({ version: 1, message: '过期视图' }) }); await pendingPoll
    expect(s.room?.version).toBe(2); expect(s.room?.message).not.toBe('过期视图')
  })
  it('stamps receipt time locally and rejects an older same-version server clock', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(10000)
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: room({ version: 2, serverTime: '2026-09-07T12:00:20Z', clientReceivedAt: -999 }) })
      .mockResolvedValueOnce({ data: room({ version: 2, serverTime: '2026-09-07T12:00:05Z', clientReceivedAt: 999999 }) })
    const s = seated(); await s.refresh()
    expect(s.room?.clientReceivedAt).toBe(10000)
    now.mockReturnValue(20000); await s.refresh()
    expect(s.room?.serverTime).toBe('2026-09-07T12:00:20Z')
    expect(s.room?.clientReceivedAt).toBe(10000)
  })
  it('does not send client clock metadata with an action', async () => {
    const post = vi.spyOn(yaomingApi, 'post').mockResolvedValue({ data: room({ version: 2 }) })
    const s = seated(); await s.act(s.room!.actions[0])
    expect(post.mock.calls[0][1]).not.toHaveProperty('clientReceivedAt')
  })
  it('serializes double clicks and includes version, token, request ID, and exact tile IDs', async () => {
    let deliver!: (value: unknown) => void
    const post = vi.spyOn(yaomingApi, 'post').mockReturnValue(new Promise(resolve => { deliver = resolve }) as never)
    const s = seated(); const first = s.act(s.room!.actions[0]); const second = await s.act(s.room!.actions[0])
    expect(second).toBe(false); expect(post).toHaveBeenCalledTimes(1)
    expect(post).toHaveBeenCalledWith('/rooms/room1/actions', expect.objectContaining({ playerId: 'p1', token: 'private-token', version: 1, requestId: expect.any(String), type: 'DISCARD', tileIds: ['t1'] }), { headers: { 'X-Resume-Token': 'private-token' } })
    deliver({ data: room({ version: 2 }) }); await first
  })
  it('retries a lost response with the same request ID and body', async () => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce(new Error('timeout')).mockResolvedValueOnce({ data: room({ version: 2 }) })
    const s = seated(); expect(await s.act(s.room!.actions[0])).toBe(true)
    expect(post).toHaveBeenCalledTimes(2)
    expect(post.mock.calls[0]).toEqual(post.mock.calls[1])
  })
  it('syncs an expired version without losing private identity', async () => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValue({ response: { status: 409, data: { message: '牌局版本已更新' } } })
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: room({ version: 3 }) })
    const s = seated(); expect(await s.act(s.room!.actions[0])).toBe(false)
    expect(s.room?.version).toBe(3); expect(s.identity?.token).toBe('private-token'); expect(s.error).toBe('牌局版本已更新')
    expect(post).toHaveBeenCalledTimes(1)
  })
  function trusteeRoom(version: number, trustee = true) {
    const view = room({ version, actions: [{ type: 'TRUSTEE', label: '切换托管', tileIds: [] }] })
    view.players[0].trustee = trustee
    return view
  }
  it('safely retries trustee recovery after 409 using a fresh version and request ID', async () => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce({ response: { status: 409 } }).mockResolvedValueOnce({ data: trusteeRoom(4, false) })
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: trusteeRoom(3) })
    const s = seated(); s.room = trusteeRoom(1)
    expect(await s.act(s.room.actions[0])).toBe(true)
    expect(s.me?.trustee).toBe(false); expect(s.error).toBe(''); expect(post).toHaveBeenCalledTimes(2)
    expect(post.mock.calls[0][1]).toMatchObject({ version: 1 })
    expect(post.mock.calls[1][1]).toMatchObject({ version: 3 })
    expect((post.mock.calls[0][1] as { requestId: string }).requestId).not.toBe((post.mock.calls[1][1] as { requestId: string }).requestId)
  })
  it('does not toggle trustee again when refresh shows the requested state already reached', async () => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce({ response: { status: 409 } })
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: trusteeRoom(3, false) })
    const s = seated(); s.room = trusteeRoom(1)
    expect(await s.act(s.room.actions[0])).toBe(true)
    expect(post).toHaveBeenCalledTimes(1); expect(s.me?.trustee).toBe(false); expect(s.error).toBe('')
  })
  it('limits trustee version-conflict retries to three writes', async () => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValue({ response: { status: 409 } })
    let version = 1
    const get = vi.spyOn(yaomingApi, 'get').mockImplementation(async () => ({ data: trusteeRoom(++version) }) as never)
    const s = seated(); s.room = trusteeRoom(1)
    expect(await s.act(s.room.actions[0])).toBe(false)
    expect(post).toHaveBeenCalledTimes(3); expect(get).toHaveBeenCalledTimes(3)
    expect(s.error).toContain('控制状态尚未切换'); expect(s.busy).toBe(false)
  })
  it.each(['room', 'player'])('stops trustee recovery if the %s changes during synchronization', async (change) => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce({ response: { status: 409 } })
    const s = seated(); s.room = trusteeRoom(1)
    vi.spyOn(yaomingApi, 'get').mockImplementation(async () => {
      if (change === 'room') { s.identity = { ...s.identity!, roomId: 'another-room' }; s.room = room({ id: 'another-room' }) }
      else { s.identity = { ...s.identity!, playerId: 'p2' }; s.room = room({ meId: 'p2' }) }
      return { data: s.room }
    })
    expect(await s.act(s.room.actions[0])).toBe(false)
    expect(post).toHaveBeenCalledTimes(1); expect(s.busy).toBe(false)
  })
  it('stops trustee retries if the action disappears after refresh', async () => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce({ response: { status: 409 } })
    const view = trusteeRoom(3); view.actions = []
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: view })
    const s = seated(); s.room = trusteeRoom(1)
    expect(await s.act(s.room.actions[0])).toBe(false); expect(post).toHaveBeenCalledTimes(1)
  })
  it('retries explicit LEAVE after 409 with a fresh version and clears identity on success', async () => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce({ response: { status: 409 } }).mockResolvedValueOnce({ data: null })
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: room({ version: 3 }) }).mockResolvedValueOnce({ data: [] })
    const s = seated()
    expect(await s.act(s.room!.actions[1])).toBe(true)
    expect(s.identity).toBeNull(); expect(s.room).toBeNull(); expect(post).toHaveBeenCalledTimes(2)
    expect(post.mock.calls[0][1]).toMatchObject({ type: 'LEAVE', version: 1 })
    expect(post.mock.calls[1][1]).toMatchObject({ type: 'LEAVE', version: 3 })
    expect((post.mock.calls[0][1] as { requestId: string }).requestId).not.toBe((post.mock.calls[1][1] as { requestId: string }).requestId)
  })
  it('limits explicit LEAVE version conflicts to three writes and preserves the seat if they fail', async () => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValue({ response: { status: 409 } })
    let version = 1
    const get = vi.spyOn(yaomingApi, 'get').mockImplementation(async () => ({ data: room({ version: ++version }) }) as never)
    const s = seated()
    expect(await s.act(s.room!.actions[1])).toBe(false)
    expect(post).toHaveBeenCalledTimes(3); expect(get).toHaveBeenCalledTimes(3)
    expect(s.identity?.token).toBe('private-token'); expect(s.room?.id).toBe('room1')
    expect(s.error).toContain('尚未退出房间'); expect(s.busy).toBe(false)
  })
  it.each(['player', 'token'])('does not retry LEAVE when the %s identity changes while refreshing', async (change) => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce({ response: { status: 409 } })
    const s = seated()
    vi.spyOn(yaomingApi, 'get').mockImplementation(async () => {
      s.identity = { ...s.identity!, ...(change === 'player' ? { playerId: 'p2' } : { token: 'replacement-token' }) }
      return { data: room({ version: 3 }) }
    })
    expect(await s.act(s.room!.actions[1])).toBe(false)
    expect(post).toHaveBeenCalledTimes(1); expect(s.busy).toBe(false)
  })
  it('does not retry LEAVE if it is no longer allowed in the synchronized room', async () => {
    const post = vi.spyOn(yaomingApi, 'post').mockRejectedValueOnce({ response: { status: 409 } })
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: room({ version: 3, actions: [] }) })
    const s = seated()
    expect(await s.act(s.room!.actions[1])).toBe(false); expect(post).toHaveBeenCalledTimes(1)
  })
  it('preserves room and identity after a connection loss', async () => {
    vi.spyOn(yaomingApi, 'get').mockRejectedValue(new Error('Network Error'))
    const s = seated(); await s.refresh()
    expect(s.room?.id).toBe('room1'); expect(s.identity?.token).toBe('private-token'); expect(s.connected).toBe(false)
  })
  it('clears the previous polling outage message once the room reconnects', async () => {
    vi.spyOn(yaomingApi, 'get').mockRejectedValueOnce({ response: { status: 500 }, message: 'Request failed with status code 500' }).mockResolvedValueOnce({ data: room({ version: 2 }) })
    const s = seated(); await s.refresh()
    expect(s.error).toBe('服务暂时不可用，正在重试连接'); expect(s.connected).toBe(false)
    await s.refresh()
    expect(s.error).toBe(''); expect(s.connected).toBe(true); expect(s.room?.version).toBe(2)
  })
  it('clears the lobby outage message after a successful automatic refresh', async () => {
    vi.spyOn(yaomingApi, 'get').mockRejectedValueOnce(new Error('Network Error')).mockResolvedValueOnce({ data: [] })
    const s = useYaomingStore(); await s.loadLobby()
    expect(s.error).toBe('网络连接中断，正在重试连接')
    await s.loadLobby(); expect(s.error).toBe(''); expect(s.connected).toBe(true)
  })
  it('does not erase a business action failure when a following room poll recovers', async () => {
    vi.spyOn(yaomingApi, 'post').mockRejectedValue({ response: { status: 400, data: { message: '当前不能和牌' } } })
    vi.spyOn(yaomingApi, 'get').mockRejectedValueOnce(new Error('Network Error')).mockResolvedValueOnce({ data: room({ version: 2 }) })
    const s = seated(); await s.act(s.room!.actions[0])
    expect(s.error).toBe('当前不能和牌')
    await s.refresh()
    expect(s.error).toBe('当前不能和牌'); expect(s.connected).toBe(true)
  })
  it('clears the seat only after a successful LEAVE', async () => {
    vi.spyOn(yaomingApi, 'post').mockResolvedValue({ data: null }); vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: [] })
    const s = seated(); await s.act(s.room!.actions[1])
    expect(s.room).toBeNull(); expect(s.identity).toBeNull()
  })
  it('generates request IDs on the public HTTP origin where randomUUID is unavailable', () => {
    vi.spyOn(crypto, 'randomUUID').mockImplementation(undefined as never)
    const descriptor = Object.getOwnPropertyDescriptor(crypto, 'randomUUID')
    Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: undefined })
    const first = createRequestId(), second = createRequestId()
    expect(first).toMatch(/^[a-f0-9]{32}$/); expect(first).not.toBe(second)
    if (descriptor) Object.defineProperty(crypto, 'randomUUID', descriptor)
  })
})

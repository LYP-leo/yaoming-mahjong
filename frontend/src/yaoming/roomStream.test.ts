import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createSseParser, openRoomStream } from './roomStream'
import { useYaomingStore, yaomingApi } from './store'
import { room } from './testFixtures'

const identity = { roomId: 'room1', playerId: 'p1', token: 'stream-private' }
const notice = (event = 'room-change', version = 2, roomId = 'room1') => `event: ${event}\ndata: ${JSON.stringify({ roomId, version, type: event === 'ready' ? 'READY' : event === 'ping' ? 'PING' : event === 'closed' ? 'CLOSED' : 'CHANGED' })}\n\n`
function streamResponse() {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const body = new ReadableStream<Uint8Array>({ start(c) { controller = c } })
  let aborted = false
  const fetcher = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
    init?.signal?.addEventListener('abort', () => { aborted = true; try { controller.error(new Error('aborted')) } catch { /* Already closed. */ } })
    return { ok: true, status: 200, headers: new Headers({ 'content-type': 'text/event-stream;charset=UTF-8' }), body } as Response
  })
  return { fetcher, send: (value: string) => controller.enqueue(new TextEncoder().encode(value)), end: () => controller.close(), aborted: () => aborted }
}
beforeEach(() => { vi.restoreAllMocks(); vi.useFakeTimers(); localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia()) })
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('bounded authenticated SSE transport', () => {
  it('parses CRLF split across chunks, multiline data, comments and consecutive messages', () => {
    const receive = vi.fn(), parse = createSseParser(receive)
    parse(': ping\r\nevent: ready\r'); parse('\ndata: {"a":\r\ndata: 1}\r\n\r'); parse('\n' + notice())
    expect(receive.mock.calls[0]).toEqual(['ready', '{"a":\n1}'])
    expect(receive.mock.calls[1][0]).toBe('room-change'); expect(receive).toHaveBeenCalledTimes(2)
  })
  it('bounds unframed and accumulated data to avoid an unbounded receive buffer', () => {
    expect(() => createSseParser(() => {})('x'.repeat(65537))).toThrow('过大')
    const parse = createSseParser(() => {})
    parse('data: ' + 'x'.repeat(40000) + '\n')
    expect(() => parse('data: ' + 'x'.repeat(40000) + '\n')).toThrow('过大')
  })
  it('uses a private header, waits for a valid ready event, ignores wrong rooms and malformed notifications', async () => {
    const stream = streamResponse(), onNotice = vi.fn(), onHealth = vi.fn()
    const close = openRoomStream(identity, { fetcher: stream.fetcher, onNotice, onHealth })
    await flushPromises()
    expect(stream.fetcher).toHaveBeenCalledWith('/api/yaoming/rooms/room1/stream?playerId=p1', expect.objectContaining({ headers: { Accept: 'text/event-stream', 'X-Resume-Token': 'stream-private' } }))
    expect(onHealth).not.toHaveBeenCalledWith(true)
    stream.send(notice('ready', 1, 'wrong') + 'event: ready\ndata: bad-json\n\n' + 'event: room-change\ndata: {"roomId":"room1","version":2,"type":"PING"}\n\n')
    await flushPromises(); expect(onNotice).not.toHaveBeenCalled()
    const encoded = notice('ready', 1); stream.send(encoded.slice(0, 13)); stream.send(encoded.slice(13))
    await flushPromises(); expect(onHealth).toHaveBeenCalledWith(true); expect(onNotice).toHaveBeenCalledWith({ roomId: 'room1', version: 1, type: 'READY' })
    close(); await flushPromises(); expect(stream.aborted()).toBe(true); expect(vi.getTimerCount()).toBe(0)
  })
  it('backs off failed connections at 1, 2 and 4 seconds while never reporting a healthy stream', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('Network Error')), onHealth = vi.fn()
    const close = openRoomStream(identity, { fetcher, onNotice: vi.fn(), onHealth })
    await flushPromises(); expect(fetcher).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1000); expect(fetcher).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1999); expect(fetcher).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1); expect(fetcher).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(4000); expect(fetcher).toHaveBeenCalledTimes(4)
    expect(onHealth).not.toHaveBeenCalledWith(true)
    close(); expect(vi.getTimerCount()).toBe(0)
  })
  it.each([401, 403, 404])('does not hammer a permanently rejected stream (%s)', async status => {
    const fetcher = vi.fn().mockResolvedValue({ ok: false, status, body: null })
    const close = openRoomStream(identity, { fetcher, onNotice: vi.fn(), onHealth: vi.fn() })
    await flushPromises(); await vi.advanceTimersByTimeAsync(60000)
    expect(fetcher).toHaveBeenCalledTimes(1); close()
  })
  it('aborts a connection that stops sending heartbeats and schedules reconnect', async () => {
    const stream = streamResponse(), onHealth = vi.fn()
    const close = openRoomStream(identity, { fetcher: stream.fetcher, onNotice: vi.fn(), onHealth })
    await flushPromises(); stream.send(notice('ready', 1)); await flushPromises()
    await vi.advanceTimersByTimeAsync(35000); await flushPromises()
    expect(stream.aborted()).toBe(true); expect(onHealth).toHaveBeenLastCalledWith(false)
    close(); expect(vi.getTimerCount()).toBe(0)
  })
  it('treats closed as a final notification and falls back without reconnecting the same departed seat', async () => {
    const stream = streamResponse(), onNotice = vi.fn()
    const close = openRoomStream(identity, { fetcher: stream.fetcher, onNotice, onHealth: vi.fn() })
    await flushPromises(); stream.send(notice('closed', 5)); await flushPromises(); await vi.advanceTimersByTimeAsync(60000)
    expect(onNotice).toHaveBeenCalledWith({ roomId: 'room1', version: 5, type: 'CLOSED' })
    expect(stream.fetcher).toHaveBeenCalledTimes(1); close()
  })
  it('does nothing when a browser fetch implementation is unavailable', async () => {
    const onHealth = vi.fn(), close = openRoomStream(identity, { onHealth, onNotice: vi.fn() })
    await flushPromises(); expect(vi.getTimerCount()).toBe(0); close()
  })
})

describe('SSE-triggered private snapshot synchronization', () => {
  function seated() { const store = useYaomingStore(); store.identity = { ...identity }; store.room = room(); return store }
  function mockApi() {
    let version = 1
    const get = vi.spyOn(yaomingApi, 'get').mockImplementation(async path => ({ data: path === '/rules' ? {} : room({ version }) }))
    return { get, advance: (next: number) => { version = next } }
  }
  it('uses 10 second backup reads on a healthy stream and 2 second polling on failure', async () => {
    const stream = streamResponse(); vi.stubGlobal('fetch', stream.fetcher)
    const api = mockApi(), store = seated()
    await store.start(); stream.send(notice('ready', 1)); await flushPromises()
    expect(store.pushHealthy).toBe(true); api.get.mockClear()
    await vi.advanceTimersByTimeAsync(9999); expect(api.get).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1); expect(api.get).toHaveBeenCalledTimes(1)
    stream.end(); await flushPromises(); api.get.mockClear()
    await vi.advanceTimersByTimeAsync(2000); expect(api.get).toHaveBeenCalledTimes(1)
    expect(store.pushHealthy).toBe(false)
    store.stop(); await flushPromises()
  })
  it('coalesces notifications while an action is busy, then reads the newest private snapshot', async () => {
    const stream = streamResponse(); vi.stubGlobal('fetch', stream.fetcher)
    const api = mockApi(), store = seated()
    await store.start(); stream.send(notice('ready', 1)); await flushPromises(); api.get.mockClear()
    let deliver!: (value: unknown) => void
    vi.spyOn(yaomingApi, 'post').mockReturnValue(new Promise(resolve => { deliver = resolve }) as never)
    const write = store.act(store.room!.actions[0])
    api.advance(4); stream.send(notice('room-change', 3) + notice('room-change', 4)); await flushPromises()
    expect(api.get).not.toHaveBeenCalled()
    deliver({ data: room({ version: 2 }) }); await write; await flushPromises()
    expect(api.get).toHaveBeenCalledTimes(1); expect(store.room?.version).toBe(4)
    store.stop(); await flushPromises()
  })
  it('ignores an old version notification and aborts the old stream on identity changes and stop', async () => {
    const first = streamResponse(), second = streamResponse()
    const fetcher = vi.fn().mockImplementationOnce(first.fetcher).mockImplementationOnce(second.fetcher)
    vi.stubGlobal('fetch', fetcher)
    const api = mockApi(), store = seated(); await store.start()
    first.send(notice('ready', 1)); await flushPromises(); api.get.mockClear()
    first.send(notice('room-change', 0)); await flushPromises(); expect(api.get).not.toHaveBeenCalled()
    store.identity = { ...identity, playerId: 'p2', token: 'other-token' }; await flushPromises()
    expect(first.aborted()).toBe(true); expect(fetcher).toHaveBeenCalledTimes(2)
    expect(fetcher.mock.calls[1][1].headers['X-Resume-Token']).toBe('other-token')
    store.stop(); await flushPromises(); await vi.advanceTimersByTimeAsync(0)
    expect(second.aborted()).toBe(true); expect(vi.getTimerCount()).toBe(0)
  })
  it('retains polling and player identity when stream establishment fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network Error')))
    const api = mockApi(), store = seated(); await store.start(); await flushPromises(); api.get.mockClear()
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.get).toHaveBeenCalledTimes(1); expect(store.identity).toEqual(identity); expect(store.connected).toBe(true)
    store.stop(); await flushPromises()
  })
  it('resets a previous healthy ten-second timer to two seconds after changing seats', async () => {
    const first = streamResponse()
    const fetcher = vi.fn().mockImplementationOnce(first.fetcher).mockRejectedValue(new Error('Network Error'))
    vi.stubGlobal('fetch', fetcher)
    const api = mockApi(), store = seated(); await store.start()
    first.send(notice('ready', 1)); await flushPromises(); api.get.mockClear()
    store.identity = { ...identity, playerId: 'p2', token: 'other-token' }; await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.get).toHaveBeenCalledTimes(1)
    store.stop(); await flushPromises()
  })
})

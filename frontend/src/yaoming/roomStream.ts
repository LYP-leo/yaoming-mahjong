import type { Identity } from './types'

export interface StreamNotice { roomId: string; version: number; type: 'READY' | 'CHANGED' | 'PING' | 'CLOSED' }
/** Incremental SSE framing: a UTF-8 code point, CRLF, or event may span chunks. */
export function createSseParser(receive: (event: string, data: string) => void) {
  let buffer = '', event = '', data: string[] = [], size = 0
  return (chunk: string) => {
    buffer += chunk
    if (buffer.length + size > 65536) throw new Error('推送消息过大')
    let end: number
    while ((end = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, end).replace(/\r$/, '')
      buffer = buffer.slice(end + 1)
      if (!line) {
        if (data.length) receive(event || 'message', data.join('\n'))
        event = ''; data = []; size = 0
      } else if (!line.startsWith(':')) {
        const colon = line.indexOf(':')
        const field = colon < 0 ? line : line.slice(0, colon)
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
        if (field === 'event') event = value
        if (field === 'data') { data.push(value); size += value.length }
      }
    }
  }
}

export function openRoomStream(identity: Identity, options: {
  fetcher?: typeof fetch; onNotice: (notice: StreamNotice) => void; onHealth: (healthy: boolean) => void;
}) {
  let stopped = false, generation = 0, attempts = 0
  let controller: AbortController | null = null
  let retry: ReturnType<typeof setTimeout> | null = null
  let watchdog: ReturnType<typeof setTimeout> | null = null
  const fetcher = options.fetcher
  function clearWatchdog() { if (watchdog) clearTimeout(watchdog); watchdog = null }
  function heartbeat() {
    clearWatchdog()
    watchdog = setTimeout(() => { options.onHealth(false); controller?.abort() }, 35000)
  }
  async function connect() {
    if (stopped || !fetcher) return
    const expected = ++generation
    const active = () => !stopped && generation === expected
    controller = new AbortController()
    let permanent = false
    heartbeat()
    try {
      const response = await fetcher(`/api/yaoming/rooms/${encodeURIComponent(identity.roomId)}/stream?playerId=${encodeURIComponent(identity.playerId)}`, {
        headers: { Accept: 'text/event-stream', 'X-Resume-Token': identity.token }, signal: controller.signal, cache: 'no-store',
      })
      if (!active()) { await response.body?.cancel(); return }
      if (!response.ok) { permanent = response.status === 401 || response.status === 403 || response.status === 404; throw new Error('推送连接失败') }
      if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('不支持实时推送')
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      const parse = createSseParser((event, data) => {
        if (!active()) return
        let notice: StreamNotice
        try { notice = JSON.parse(data) } catch { return }
        const expectedTypes: Record<string, string> = { ready: 'READY', 'room-change': 'CHANGED', ping: 'PING', closed: 'CLOSED' }
        if (notice.roomId !== identity.roomId || notice.type !== expectedTypes[event] || !Number.isSafeInteger(notice.version) || notice.version < 0) return
        heartbeat()
        attempts = 0
        options.onHealth(notice.type !== 'CLOSED')
        options.onNotice(notice)
        if (notice.type === 'CLOSED') { permanent = true; controller?.abort() }
      })
      try {
        while (active()) {
          const { value, done } = await reader.read()
          if (done) break
          parse(decoder.decode(value, { stream: true }))
        }
      } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
    } catch { /* Polling remains active while an authenticated stream reconnects. */ }
    finally {
      if (active()) {
        clearWatchdog(); options.onHealth(false)
        if (!permanent) retry = setTimeout(() => { retry = null; void connect() }, Math.min(30000, 1000 * 2 ** Math.min(attempts++, 5)))
      }
    }
  }
  void connect()
  return () => { stopped = true; generation++; if (retry) clearTimeout(retry); clearWatchdog(); controller?.abort(); options.onHealth(false) }
}

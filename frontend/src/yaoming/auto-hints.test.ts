import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import HintsPanel from './HintsPanel.vue'
import { yaomingApi } from './store'
import { room, tile } from './testFixtures'
import type { HintResponse, Identity, WaitHint } from './types'

const identity: Identity = { roomId: 'room1', playerId: 'p1', token: 'private-auto-hints-token' }
const response = (version = 1, count = 1, overrides: Partial<HintResponse> = {}): HintResponse => ({
  roomId: 'room1', playerId: 'p1', version,
  analysis: { mode: 'WAIT', note: `不可见说明版本${version}`, discards: [], waits: [
    { tile, unseenCount: count, canTsumo: true, tsumoFan: 4, canRon: false, ronFan: 3, ronReason: '不可见点和限制' },
  ] }, ...overrides,
})
const mounted: VueWrapper[] = []
function panel(props: { identity: Identity | null; room: ReturnType<typeof room> } = { identity, room: room() }) {
  const wrapper = mount(HintsPanel, { props }); mounted.push(wrapper); return wrapper
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((accept, refuse) => { resolve = accept; reject = refuse })
  return { promise, resolve, reject }
}
async function advance(milliseconds = 200) { await vi.advanceTimersByTimeAsync(milliseconds); await flushPromises() }
const counts = (wrapper: VueWrapper) => wrapper.findAll('.ym-hint-count').map(node => node.text())
function visibleText(wrapper: VueWrapper) {
  const copy = wrapper.element.cloneNode(true) as Element
  copy.querySelectorAll('svg title,svg desc').forEach(node => node.remove())
  return copy.textContent?.trim()
}
function noControlsOrProse(wrapper: VueWrapper) {
  expect(wrapper.findAll('button,h1,h2,h3,h4,p,details,summary,[role="alert"],[role="status"]')).toHaveLength(0)
  expect(wrapper.text()).not.toMatch(/版本|不可见|分析中|加载|暂无|重试|听牌辅助/)
}
beforeEach(() => { vi.restoreAllMocks(); vi.useFakeTimers(); localStorage.clear(); sessionStorage.clear() })
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); vi.clearAllTimers(); vi.useRealTimers() })

describe('quiet automatic scheduling', () => {
  it.each(['NEED_DRAW', 'NEED_DISCARD', 'REACTION'])('silently analyzes %s after 200ms without any button', async status => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: response() })
    const wrapper = panel({ identity, room: room({ status }) })
    expect(wrapper.text()).toBe(''); noControlsOrProse(wrapper)
    await advance(199); expect(get).not.toHaveBeenCalled()
    await advance(1); expect(get).toHaveBeenCalledTimes(1); expect(counts(wrapper)).toEqual(['1张'])
    expect(get).toHaveBeenCalledWith('/rooms/room1/hints', expect.objectContaining({ params: { playerId: 'p1' }, headers: { 'X-Resume-Token': identity.token }, signal: expect.any(AbortSignal) }))
    expect(visibleText(wrapper)).toBe('1张自摸4番'); noControlsOrProse(wrapper); expect(wrapper.html()).not.toContain(identity.token)
  })
  it.each(['WAITING', 'HAND_END', 'MATCH_END'])('makes no requests and displays no text in %s', async status => {
    const get = vi.spyOn(yaomingApi, 'get'); const wrapper = panel({ identity, room: room({ status }) })
    await advance(10000); expect(get).not.toHaveBeenCalled(); expect(wrapper.text()).toBe('')
  })
  it.each([null, { ...identity, roomId: 'other' }, { ...identity, playerId: 'p2' }, { ...identity, token: '' }])('rejects an absent or mismatched identity %o', async invalid => {
    const get = vi.spyOn(yaomingApi, 'get'); const wrapper = panel({ identity: invalid, room: room() })
    await advance(10000); expect(get).not.toHaveBeenCalled(); expect(wrapper.text()).toBe('')
  })
  it('does not calculate a missing seat', async () => {
    const get = vi.spyOn(yaomingApi, 'get'); panel({ identity, room: room({ players: [] }) })
    await advance(10000); expect(get).not.toHaveBeenCalled()
  })
  it('coalesces rapid versions into a request for only the final version', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: response(3, 3) }); const wrapper = panel()
    await advance(100); await wrapper.setProps({ room: room({ version: 2 }) })
    await advance(100); await wrapper.setProps({ room: room({ version: 3 }) })
    await advance(199); expect(get).not.toHaveBeenCalled()
    await advance(1); expect(get).toHaveBeenCalledTimes(1); expect(counts(wrapper)).toEqual(['3张'])
  })
  it('preserves a pending request and its result across same-version heartbeat object replacements', async () => {
    const pending = deferred<{ data: HintResponse }>()
    const get = vi.spyOn(yaomingApi, 'get').mockReturnValueOnce(pending.promise as never); const wrapper = panel(); await advance()
    const signal = get.mock.calls[0][1]!.signal as AbortSignal
    await wrapper.setProps({ identity: { ...identity }, room: room({ serverTime: '2026-09-09T05:00:00Z', clientReceivedAt: 10 }) }); await advance(5000)
    expect(get).toHaveBeenCalledTimes(1); expect(signal.aborted).toBe(false)
    pending.resolve({ data: response() }); await flushPromises(); const node = wrapper.get('.ym-hint-waits').element
    await wrapper.setProps({ room: room({ serverTime: '2026-09-09T05:00:01Z', clientReceivedAt: 20 }) }); await advance(5000)
    expect(get).toHaveBeenCalledTimes(1); expect(wrapper.get('.ym-hint-waits').element).toBe(node)
  })
  it('starts automatically when a waiting room enters play without changing its version', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: response() }); const wrapper = panel({ identity, room: room({ status: 'WAITING' }) })
    await advance(1000); expect(get).not.toHaveBeenCalled()
    await wrapper.setProps({ room: room({ status: 'NEED_DRAW' }) }); await advance()
    expect(get).toHaveBeenCalledTimes(1); expect(counts(wrapper)).toEqual(['1张'])
  })
})

describe('stable successful display during background updates', () => {
  it('does not mutate children, replace faces or reset scrolling for pending work or identical visible output', async () => {
    const pending = deferred<{ data: HintResponse }>()
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response() }).mockReturnValueOnce(pending.promise as never)
    const wrapper = panel(); await advance()
    const content = wrapper.get('.ym-hints-content').element as HTMLElement
    const row = wrapper.get('.ym-hint-tile').element, face = wrapper.get('svg.mahjong-art').element
    content.scrollTop = 37
    const mutations: MutationRecord[] = [], observer = new MutationObserver(records => mutations.push(...records))
    observer.observe(content, { childList: true, subtree: true, attributes: true, characterData: true })
    try {
      await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
      expect(get).toHaveBeenCalledTimes(2); expect(wrapper.get('.ym-hint-tile').element).toBe(row); expect(counts(wrapper)).toEqual(['1张'])
      const next = response(2); next.analysis.waits[0] = { ...next.analysis.waits[0], tile: { ...tile, id: 'new-representative-id' }, ronFan: 8, ronReason: '另一个不显示的原因' }
      pending.resolve({ data: next }); await flushPromises()
      expect(wrapper.get('svg.mahjong-art').element).toBe(face); expect(wrapper.get('.ym-hint-tile').element).toBe(row)
      expect(content.scrollTop).toBe(37); expect(mutations).toHaveLength(0); noControlsOrProse(wrapper)
    } finally { observer.disconnect() }
  })
  it.each(['WAIT', 'DISCARD'] as const)('updates only visible fan text after a successful %s response without replacing tiles or resetting scrolling', async mode => {
    const first = response(), next = response(2), pending = deferred<{ data: HintResponse }>()
    first.analysis.mode = mode; next.analysis.mode = mode
    next.analysis.waits[0] = { ...next.analysis.waits[0], canRon: true, ronFan: 4, tsumoFan: 5 }
    if (mode === 'DISCARD') {
      first.analysis.discards = [{ tile, waits: first.analysis.waits }]
      next.analysis.discards = [{ tile, waits: next.analysis.waits }]
    }
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: first }).mockReturnValueOnce(pending.promise as never)
    const wrapper = panel(); await advance()
    const content = wrapper.get('.ym-hints-content').element as HTMLElement, row = wrapper.get('.ym-hint-tile').element
    const faces = wrapper.findAll('svg.mahjong-art').map(face => face.element), count = wrapper.get('.ym-hint-count').element
    content.scrollTop = 37
    await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(wrapper.get('.ym-hint-fans').text()).toBe('自摸4番')
    pending.resolve({ data: next }); await flushPromises()
    expect(wrapper.findAll('.ym-hint-fans > span').map(line => line.text())).toEqual(['点和4番', '自摸5番'])
    expect(wrapper.findAll('svg.mahjong-art').map(face => face.element)).toEqual(faces)
    expect(wrapper.get('.ym-hint-tile').element).toBe(row); expect(wrapper.get('.ym-hint-count').element).toBe(count)
    expect(content.scrollTop).toBe(37); noControlsOrProse(wrapper)
  })
  it('updates a same-valued fan when eligibility changes from self-draw-only to both methods', async () => {
    const next = response(2); next.analysis.waits[0].canRon = true; next.analysis.waits[0].ronFan = 4
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response() }).mockResolvedValueOnce({ data: next })
    const wrapper = panel(); await advance(); const face = wrapper.get('svg.mahjong-art').element
    expect(wrapper.get('.ym-hint-fans').text()).toBe('自摸4番')
    await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(wrapper.get('.ym-hint-fans').text()).toBe('4番'); expect(wrapper.get('svg.mahjong-art').element).toBe(face)
  })
  it('publishes a fan-only change while keeping the same tile and count nodes', async () => {
    const next = response(2); next.analysis.waits[0].tsumoFan = 5
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response() }).mockResolvedValueOnce({ data: next })
    const wrapper = panel(); await advance(); const face = wrapper.get('svg.mahjong-art').element, count = wrapper.get('.ym-hint-count').element
    await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(wrapper.get('.ym-hint-fans').text()).toBe('自摸5番')
    expect(wrapper.get('svg.mahjong-art').element).toBe(face); expect(wrapper.get('.ym-hint-count').element).toBe(count)
  })
  it('changes a count only after the new successful response, retaining the tile element', async () => {
    const pending = deferred<{ data: HintResponse }>()
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response() }).mockReturnValueOnce(pending.promise as never)
    const wrapper = panel(); await advance(); const face = wrapper.get('svg.mahjong-art').element
    await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(counts(wrapper)).toEqual(['1张']); pending.resolve({ data: response(2, 0) }); await flushPromises()
    expect(counts(wrapper)).toEqual(['0张']); expect(wrapper.get('svg.mahjong-art').element).toBe(face)
    expect(wrapper.get('.ym-hint-tile').classes()).toContain('ym-hint-zero')
  })
  it('clears previously displayed candidates only when a successful empty result arrives', async () => {
    const pending = deferred<{ data: HintResponse }>()
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response() }).mockReturnValueOnce(pending.promise as never)
    const wrapper = panel(); await advance(); await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(counts(wrapper)).toEqual(['1张'])
    const next = response(2); next.analysis.waits = []; pending.resolve({ data: next }); await flushPromises()
    expect(wrapper.text()).toBe(''); expect(wrapper.findAll('svg')).toHaveLength(0); noControlsOrProse(wrapper)
  })
  it('normalizes different candidate order and duplicate physical discard representatives without replacing rows', async () => {
    const first = response(), other: WaitHint = { ...first.analysis.waits[0], tile: { ...tile, id: 'dot', suit: 'DOTS' }, unseenCount: 2 }
    first.analysis.mode = 'DISCARD'; first.analysis.discards = [
      { tile, waits: [other, first.analysis.waits[0]] }, { tile: { ...tile, id: 'duplicate-copy' }, waits: [first.analysis.waits[0], other] },
    ]
    const next = response(2); next.analysis.mode = 'DISCARD'; next.analysis.discards = [
      { tile: { ...tile, id: 'third-representative' }, waits: [first.analysis.waits[0], other] },
    ]
    vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: first }).mockResolvedValueOnce({ data: next })
    const wrapper = panel(); await advance(); expect(wrapper.findAll('.ym-discard-hint')).toHaveLength(1)
    const row = wrapper.get('.ym-discard-hint').element, faces = wrapper.findAll('svg.mahjong-art').map(face => face.element)
    expect(counts(wrapper)).toEqual(['1张', '2张'])
    await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(wrapper.get('.ym-discard-hint').element).toBe(row)
    expect(wrapper.findAll('svg.mahjong-art').map(face => face.element)).toEqual(faces)
    expect(wrapper.findAll('.ym-hint-arrow')).toHaveLength(1); noControlsOrProse(wrapper)
  })
})

describe('scope privacy and stale request isolation', () => {
  it.each([{ round: 2 }, { dealerSeat: 1 }, { meId: 'p2' }, { id: 'other-room' }, { status: 'HAND_END' }, { status: 'MATCH_END' }])('immediately clears a successful display when the hand scope changes %o', async change => {
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: response() }); const wrapper = panel(); await advance()
    expect(counts(wrapper)).toEqual(['1张']); await wrapper.setProps({ room: room(change) })
    expect(wrapper.text()).toBe(''); expect(wrapper.find('.ym-hint-waits').exists()).toBe(false)
  })
  it('aborts a stale version and ignores its late response after a newer result is shown', async () => {
    const old = deferred<{ data: HintResponse }>()
    const get = vi.spyOn(yaomingApi, 'get').mockReturnValueOnce(old.promise as never).mockResolvedValueOnce({ data: response(2, 2) })
    const wrapper = panel(); await advance(); const signal = get.mock.calls[0][1]!.signal as AbortSignal
    await wrapper.setProps({ room: room({ version: 2 }) }); expect(signal.aborted).toBe(true); await advance()
    old.resolve({ data: response(1, 4) }); await flushPromises()
    expect(counts(wrapper)).toEqual(['2张']); expect(wrapper.emitted('sync')).toBeUndefined()
  })
  it('clears immediately on token renewal and ignores a late failure from the old credential', async () => {
    const old = deferred<{ data: HintResponse }>()
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response() }).mockReturnValueOnce(old.promise as never).mockResolvedValueOnce({ data: response(2, 2) })
    const wrapper = panel(); await advance(); await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    await wrapper.setProps({ identity: { ...identity, token: 'renewed-token' } }); expect(wrapper.text()).toBe('')
    expect((get.mock.calls[1][1]!.signal as AbortSignal).aborted).toBe(true); await advance()
    old.reject({ response: { status: 403 } }); await flushPromises()
    expect(counts(wrapper)).toEqual(['2张']); expect(wrapper.emitted('sync')).toBeUndefined()
  })
  it('isolates a new room and player even if the server ignores abort', async () => {
    const old = deferred<{ data: HintResponse }>()
    const get = vi.spyOn(yaomingApi, 'get').mockReturnValueOnce(old.promise as never).mockResolvedValueOnce({ data: response(1, 3, { roomId: 'other-room', playerId: 'p2' }) })
    const wrapper = panel(); await advance()
    await wrapper.setProps({ identity: { roomId: 'other-room', playerId: 'p2', token: 'new-seat-token' }, room: room({ id: 'other-room', meId: 'p2' }) }); await advance()
    old.resolve({ data: response(1, 4) }); await flushPromises(); expect(counts(wrapper)).toEqual(['3张'])
    expect(get).toHaveBeenLastCalledWith('/rooms/other-room/hints', expect.objectContaining({ params: { playerId: 'p2' }, headers: { 'X-Resume-Token': 'new-seat-token' } }))
    expect(wrapper.html()).not.toMatch(/private-auto-hints-token|new-seat-token/)
  })
  it.each(['HAND_END', 'MATCH_END'])('aborts and suppresses an in-flight response after %s', async status => {
    const pending = deferred<{ data: HintResponse }>(), get = vi.spyOn(yaomingApi, 'get').mockReturnValueOnce(pending.promise as never)
    const wrapper = panel(); await advance(); const signal = get.mock.calls[0][1]!.signal as AbortSignal
    await wrapper.setProps({ room: room({ status }) }); expect(signal.aborted).toBe(true)
    pending.resolve({ data: response() }); await flushPromises(); await advance(100000)
    expect(wrapper.text()).toBe(''); expect(get).toHaveBeenCalledTimes(1); expect(wrapper.emitted('sync')).toBeUndefined()
  })
  it('cancels the initial debounce on unmount', async () => {
    const get = vi.spyOn(yaomingApi, 'get'), wrapper = panel(); wrapper.unmount(); await advance(100000)
    expect(get).not.toHaveBeenCalled(); expect(vi.getTimerCount()).toBe(0)
  })
  it('aborts pending work on unmount and never schedules retries or emits sync from a late response', async () => {
    const pending = deferred<{ data: HintResponse }>(), get = vi.spyOn(yaomingApi, 'get').mockReturnValueOnce(pending.promise as never)
    const wrapper = panel(); await advance(); const signal = get.mock.calls[0][1]!.signal as AbortSignal
    wrapper.unmount(); expect(signal.aborted).toBe(true); pending.resolve({ data: response(99) }); await flushPromises(); await advance(100000)
    expect(get).toHaveBeenCalledTimes(1); expect(wrapper.emitted('sync')).toBeUndefined(); expect(vi.getTimerCount()).toBe(0)
  })
})

describe('quiet bounded background recovery', () => {
  it('retries at 1s and 3s, then only once every 30s without visible error UI', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockRejectedValue(new Error('offline')), wrapper = panel(); await advance()
    expect(get).toHaveBeenCalledTimes(1); expect(wrapper.text()).toBe('')
    await advance(999); expect(get).toHaveBeenCalledTimes(1); await advance(1); expect(get).toHaveBeenCalledTimes(2)
    await advance(2999); expect(get).toHaveBeenCalledTimes(2); await advance(1); expect(get).toHaveBeenCalledTimes(3)
    await advance(29999); expect(get).toHaveBeenCalledTimes(3); await advance(1); expect(get).toHaveBeenCalledTimes(4)
    await advance(29999); expect(get).toHaveBeenCalledTimes(4); await advance(1); expect(get).toHaveBeenCalledTimes(5)
    expect(wrapper.text()).toBe(''); noControlsOrProse(wrapper)
  })
  it('preserves 1s, 3s and 30s cooldowns through changing room versions', async () => {
    const times: number[] = [], get = vi.spyOn(yaomingApi, 'get').mockImplementation(() => { times.push(Date.now()); return Promise.reject(new Error('offline')) })
    const wrapper = panel(); await advance()
    for (let version = 2; version <= 5; version++) { await advance(200); await wrapper.setProps({ room: room({ version }) }) }
    await advance(199); expect(get).toHaveBeenCalledTimes(1); await advance(1); expect(get).toHaveBeenCalledTimes(2)
    for (let version = 6; version <= 8; version++) { await advance(700); await wrapper.setProps({ room: room({ version }) }) }
    await advance(899); expect(get).toHaveBeenCalledTimes(2); await advance(1); expect(get).toHaveBeenCalledTimes(3)
    for (let version = 9; version <= 13; version++) { await advance(5000); await wrapper.setProps({ room: room({ version }) }) }
    await advance(4999); expect(get).toHaveBeenCalledTimes(3); await advance(1); expect(get).toHaveBeenCalledTimes(4)
    expect(times.slice(1).map((time, index) => time - times[index])).toEqual([1000, 3000, 30000])
  })
  it.each([500, 503, 400])('retains old tiles on transient status %s and replaces them only after a successful retry', async status => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response() })
      .mockRejectedValueOnce({ response: { status, data: { message: '牌局保存失败，本次操作未生效，请稍后重试' } } }).mockResolvedValueOnce({ data: response(2, 2) })
    const wrapper = panel(); await advance(); const displayed = wrapper.get('svg.mahjong-art').element
    await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(wrapper.get('svg.mahjong-art').element).toBe(displayed); expect(counts(wrapper)).toEqual(['1张']); noControlsOrProse(wrapper)
    await advance(1000); expect(get).toHaveBeenCalledTimes(3); expect(counts(wrapper)).toEqual(['2张']); expect(wrapper.emitted('sync')).toBeUndefined()
  })
  it('recovers automatically after a 30s retry and resets normal version updates to 200ms', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockRejectedValue(new Error('offline')), wrapper = panel()
    await advance(); await advance(1000); await advance(3000); expect(get).toHaveBeenCalledTimes(3)
    get.mockResolvedValueOnce({ data: response() }); await advance(30000); expect(counts(wrapper)).toEqual(['1张'])
    get.mockResolvedValueOnce({ data: response(2, 2) }); await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(get).toHaveBeenCalledTimes(5); expect(counts(wrapper)).toEqual(['2张']); await advance(60000); expect(get).toHaveBeenCalledTimes(5)
  })
  it.each([400, 401, 403, 404])('clears previous hints on authorization status %s, asks for sync once and blocks across versions', async status => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response() }).mockRejectedValue({ response: { status, data: { message: '身份无效' } } })
    const wrapper = panel(); await advance(); await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(wrapper.text()).toBe(''); expect(wrapper.emitted('sync')).toHaveLength(1)
    for (let version = 3; version <= 5; version++) { await wrapper.setProps({ room: room({ version }) }); await advance(60000) }
    expect(get).toHaveBeenCalledTimes(2); expect(wrapper.emitted('sync')).toHaveLength(1); noControlsOrProse(wrapper)
    get.mockResolvedValueOnce({ data: response(5, 2) }); await wrapper.setProps({ identity: { ...identity, token: 'renewed-token' } }); await advance()
    expect(get).toHaveBeenCalledTimes(3); expect(counts(wrapper)).toEqual(['2张'])
  })
  it('cancels background retry timers on unmount', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockRejectedValue(new Error('offline')), wrapper = panel(); await advance(); wrapper.unmount(); await advance(100000)
    expect(get).toHaveBeenCalledTimes(1); expect(vi.getTimerCount()).toBe(0)
  })
  it('keeps known-good faces for a newer-version response, emits once per request, and retries on the bounded schedule', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response() }).mockResolvedValue({ data: response(3, 3) })
    const wrapper = panel(); await advance(); const node = wrapper.get('svg.mahjong-art').element
    await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(wrapper.get('svg.mahjong-art').element).toBe(node); expect(counts(wrapper)).toEqual(['1张']); expect(wrapper.emitted('sync')).toHaveLength(1)
    await advance(1000); await advance(3000); expect(get).toHaveBeenCalledTimes(4); expect(wrapper.emitted('sync')).toHaveLength(3)
    await advance(29999); expect(get).toHaveBeenCalledTimes(4); await advance(1); expect(get).toHaveBeenCalledTimes(5)
    expect(wrapper.emitted('sync')).toHaveLength(4); expect(counts(wrapper)).toEqual(['1张'])
  })
  it('analyzes the synchronized version after its cooldown and stops retrying on success', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: response(2, 2) }), wrapper = panel(); await advance()
    expect(wrapper.text()).toBe(''); expect(wrapper.emitted('sync')).toHaveLength(1)
    await wrapper.setProps({ room: room({ version: 2 }) }); await advance(999); expect(get).toHaveBeenCalledTimes(1)
    await advance(1); expect(get).toHaveBeenCalledTimes(2); expect(counts(wrapper)).toEqual(['2张'])
    await advance(60000); expect(get).toHaveBeenCalledTimes(2)
  })
  it('never displays an older mismatched response or asks the parent to roll its version backward', async () => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data: response(1, 4) }), wrapper = panel({ identity, room: room({ version: 2 }) })
    await advance(); await advance(1000); await advance(3000)
    expect(get).toHaveBeenCalledTimes(3); expect(wrapper.text()).toBe(''); expect(wrapper.emitted('sync')).toBeUndefined()
    await advance(29999); expect(get).toHaveBeenCalledTimes(3)
  })
  it.each([{ roomId: 'unexpected' }, { playerId: 'p2' }])('clears successful hints on a mismatched response identity %o and blocks automatic retries', async mismatch => {
    const get = vi.spyOn(yaomingApi, 'get').mockResolvedValueOnce({ data: response() }).mockResolvedValue({ data: response(2, 3, mismatch) })
    const wrapper = panel(); await advance(); await wrapper.setProps({ room: room({ version: 2 }) }); await advance()
    expect(wrapper.text()).toBe(''); expect(wrapper.emitted('sync')).toHaveLength(1)
    await wrapper.setProps({ room: room({ version: 3 }) }); await advance(100000)
    expect(get).toHaveBeenCalledTimes(2); expect(wrapper.emitted('sync')).toHaveLength(1)
  })
})

describe('tile-only eligible candidates', () => {
  it.each(['WAIT', 'DISCARD'] as const)('shows eligible zero/one counts without explanatory text in %s mode', async mode => {
    const data = response(), first = data.analysis.waits[0]
    data.analysis.mode = mode; data.analysis.waits = [{ ...first, unseenCount: 0 }, { ...first, tile: { ...tile, id: 'dots', suit: 'DOTS' }, unseenCount: 1 }]
    if (mode === 'DISCARD') data.analysis.discards = [{ tile, waits: data.analysis.waits }]
    const before = JSON.stringify(data); vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data }); const post = vi.spyOn(yaomingApi, 'post')
    const wrapper = panel(); await advance(); expect(counts(wrapper)).toEqual(['0张', '1张']); expect(visibleText(wrapper)).toBe('0张自摸4番1张自摸4番')
    expect(wrapper.get('.ym-hint-count').attributes('aria-label')).toContain('不是牌墙剩余张数'); noControlsOrProse(wrapper)
    if (mode === 'DISCARD') { expect(wrapper.get('.ym-discard-hint').element.tagName).toBe('DIV'); expect(wrapper.findAll('.ym-hint-arrow')).toHaveLength(1) }
    expect(post).not.toHaveBeenCalled(); expect(wrapper.emitted('action')).toBeUndefined(); expect(JSON.stringify(data)).toBe(before)
  })
  it.each(['WAIT', 'DISCARD', 'UNAVAILABLE'] as const)('is empty when %s has no eligible winning candidate, even with a nonempty note', async mode => {
    const data = response(); data.analysis.mode = mode; data.analysis.waits[0].canTsumo = false
    if (mode === 'DISCARD') data.analysis.discards = [{ tile, waits: data.analysis.waits }]
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data }); const wrapper = panel(); await advance()
    expect(wrapper.text()).toBe(''); expect(wrapper.findAll('svg')).toHaveLength(0); noControlsOrProse(wrapper)
  })
  it('keeps a ron-only candidate and filters a candidate that can neither tsumo nor ron', async () => {
    const data = response(), first = data.analysis.waits[0]
    data.analysis.waits = [{ ...first, canTsumo: false, canRon: true, ronFan: 4 }, { ...first, tile: { ...tile, suit: 'DOTS' }, canTsumo: false, canRon: false }]
    vi.spyOn(yaomingApi, 'get').mockResolvedValue({ data }); const wrapper = panel(); await advance()
    expect(counts(wrapper)).toEqual(['1张']); expect(wrapper.findAll('svg.mahjong-art')).toHaveLength(1)
    expect(wrapper.get('.ym-hint-fans').text()).toBe('点和4番')
  })
})

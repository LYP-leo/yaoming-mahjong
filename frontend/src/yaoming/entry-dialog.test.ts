import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import YaomingApp from './YaomingApp.vue'
import { useYaomingStore } from './store'

let wrapper: VueWrapper | undefined
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })

function lobby() {
  const store = useYaomingStore()
  vi.spyOn(store, 'start').mockResolvedValue()
  vi.spyOn(store, 'stop').mockImplementation(() => {})
  const create = vi.spyOn(store, 'create').mockResolvedValue(false)
  const join = vi.spyOn(store, 'join').mockResolvedValue(false)
  const resume = vi.spyOn(store, 'resume').mockResolvedValue(false)
  wrapper = mount(YaomingApp, { attachTo: document.body })
  return { store, create, join, resume }
}
async function open(label = '创建牌局') {
  await wrapper!.findAll('button').find(button => button.text().includes(label))!.trigger('click')
  expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(true)
}

describe('lobby entry dialog dismissal', () => {
  it.each(['创建牌局', '创建第一桌', '输入房间号', '已有座位？'])('closes %s on a backdrop click without sending a request', async label => {
    const { create, join, resume } = lobby()
    await open(label)
    await wrapper!.get('.ym-overlay').trigger('click')
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(false)
    expect(create).not.toHaveBeenCalled(); expect(join).not.toHaveBeenCalled(); expect(resume).not.toHaveBeenCalled()
  })

  it('closes on an outside notification even if it intercepts bubbling above the scrim', async () => {
    const { store, create } = lobby()
    await open()
    store.error = '测试连接错误'
    await flushPromises()
    const error = wrapper!.get('.ym-error > span')
    error.element.addEventListener('click', event => event.stopPropagation())
    await error.trigger('click')
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(false)
    expect(create).not.toHaveBeenCalled()
  })

  it('closes for an outside document click, not only direct scrim targets', async () => {
    lobby(); await open()
    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(false)
  })

  it.each(['form.ym-entry-dialog', '.ym-entry-dialog h2', '.ym-entry-dialog p', '.ym-entry-dialog label', '.ym-entry-dialog input'])('keeps the dialog open when clicking %s', async selector => {
    const { create } = lobby(); await open()
    await wrapper!.get(selector).trigger('pointerdown')
    await wrapper!.get(selector).trigger('click')
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(true)
    expect(create).not.toHaveBeenCalled()
  })

  it('does not dismiss when a drag starts in an input and releases outside, but the next outside click closes', async () => {
    lobby(); await open()
    await wrapper!.get('.ym-entry-dialog input').trigger('pointerdown')
    await wrapper!.get('.ym-overlay').trigger('pointerup')
    await wrapper!.get('.ym-overlay').trigger('click')
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(true)
    await wrapper!.get('.ym-overlay').trigger('pointerdown')
    await wrapper!.get('.ym-overlay').trigger('click')
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(false)
  })

  it('preserves typed fields on dismiss and reopen, and keeps the close button working', async () => {
    lobby(); await open()
    const inputs = wrapper!.findAll('.ym-entry-dialog input')
    await inputs[0].setValue('测试玩家'); await inputs[1].setValue('我的牌局')
    await wrapper!.get('.ym-overlay').trigger('click')
    await open()
    expect(wrapper!.findAll<HTMLInputElement>('.ym-entry-dialog input').map(input => input.element.value)).toEqual(['测试玩家', '我的牌局'])
    await wrapper!.get('[aria-label="关闭入座窗口"]').trigger('click')
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(false)
  })

  it('keeps submission and its error message inside the dialog, then closes on success', async () => {
    const { store, create } = lobby(); await open()
    await wrapper!.get('.ym-entry-dialog input').setValue('玩家')
    await wrapper!.get('form.ym-entry-dialog').trigger('submit')
    expect(create).toHaveBeenCalledExactlyOnceWith('今晚来一局', '玩家', 'yaoming-3p')
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(true)
    store.error = '重试创建'; await flushPromises()
    await wrapper!.get('.ym-form-error').trigger('click')
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(true)
    create.mockResolvedValue(true)
    await wrapper!.get('form.ym-entry-dialog').trigger('submit'); await flushPromises()
    expect(create).toHaveBeenCalledTimes(2)
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(false)
  })

  it('does not dismiss unrelated settings dialogs with the entry handler', async () => {
    lobby(); await open()
    await wrapper!.get('[aria-label="关闭入座窗口"]').trigger('click')
    await wrapper!.findAll('.ym-nav-links button').find(button => button.text() === '设置')!.trigger('click')
    await wrapper!.get('.ym-settings-dialog input').trigger('click')
    expect(wrapper!.find('.ym-settings-dialog').exists()).toBe(true)
  })

  it.each(['设置', '创建牌局'])('consumes the outside click instead of activating the underlying %s button', async label => {
    const { create } = lobby(); await open()
    await wrapper!.findAll('button').find(button => button.text().includes(label))!.trigger('click')
    expect(wrapper!.find('form.ym-entry-dialog').exists()).toBe(false)
    expect(wrapper!.find('.ym-settings-dialog').exists()).toBe(false)
    expect(create).not.toHaveBeenCalled()
  })

  it('removes document listeners on unmount', async () => {
    const remove = vi.spyOn(document, 'removeEventListener')
    lobby(); await open(); wrapper!.unmount(); wrapper = undefined
    expect(remove.mock.calls.some(([type, , capture]) => type === 'click' && capture === true)).toBe(true)
    expect(remove.mock.calls.some(([type, , capture]) => type === 'pointerdown' && capture === true)).toBe(true)
  })
})

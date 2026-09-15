import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { useBodyViewport } from './useBodyViewport'

describe('yaoming viewport isolation', () => {
  it('removes a host body minimum during the game and restores it after unmount', () => {
    const hostStyle = document.createElement('style')
    hostStyle.textContent = 'body { min-width: 320px; }'
    const gameStyle = document.createElement('style')
    gameStyle.textContent = readFileSync(resolve(process.cwd(), 'src/yaoming/playability.css'), 'utf8')
    document.head.append(hostStyle, gameStyle)
    const surface = defineComponent({ setup() { useBodyViewport(); return () => h('div', { class: 'ym-app' }) } })
    const wrapper = mount(surface, { attachTo: document.body })
    try {
      expect(document.body.classList.contains('ym-active-body')).toBe(true)
      expect(parseFloat(getComputedStyle(document.body).minWidth)).toBe(0)
      wrapper.unmount()
      expect(document.body.classList.contains('ym-active-body')).toBe(false)
      expect(parseFloat(getComputedStyle(document.body).minWidth)).toBe(320)
    } finally { if (wrapper.exists()) wrapper.unmount(); hostStyle.remove(); gameStyle.remove() }
  })
})

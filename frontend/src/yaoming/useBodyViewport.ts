import { onMounted, onUnmounted } from 'vue'

export function useBodyViewport() {
  onMounted(() => document.body.classList.add('ym-active-body'))
  onUnmounted(() => document.body.classList.remove('ym-active-body'))
}

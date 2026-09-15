import { createApp } from 'vue'
import HintsPreview from './HintsPreview.vue'

// This separate Vite development entry is not included in the production build.
if (import.meta.env.DEV) createApp(HintsPreview).mount('#app')

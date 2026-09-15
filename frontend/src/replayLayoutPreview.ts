import { createApp } from 'vue'
import ReplayLayoutPreview from './yaoming/dev/ReplayLayoutPreview.vue'

// Only this development HTML imports the synthetic replay and its network-blocking adapter.
createApp(ReplayLayoutPreview).mount('#app')

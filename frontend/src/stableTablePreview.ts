import { createApp } from 'vue'
import StableTablePreview from './yaoming/dev/StableTablePreview.vue'

// Dev-only HTML entry: no room identity, API adapter or application store is mounted.
createApp(StableTablePreview).mount('#app')

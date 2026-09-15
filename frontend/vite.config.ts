import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // API CORS is enforced by Spring. Disabling Vite's own CORS middleware
    // lets OPTIONS requests reach the same proxy target as POST requests.
    cors: false,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: { environment: 'jsdom' },
})

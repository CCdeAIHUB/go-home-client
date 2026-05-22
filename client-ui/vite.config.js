import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: './',
  plugins: [vue()],
  server: {
    proxy: {
      '/api': process.env.GO_HOME_CONTROL_ORIGIN || 'http://127.0.0.1:18092'
    }
  }
})

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 3001,
    proxy: {
      '/api/recommendations': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api/simulator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})

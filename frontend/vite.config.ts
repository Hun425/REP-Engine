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
      '/api/v1/recommendations': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/v1/simulator': {
        target: 'http://localhost:8084',
        changeOrigin: true,
      },
      '/actuator/simulator': {
        target: 'http://localhost:8084',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/actuator\/simulator/, '/actuator'),
      },
      '/actuator/behavior-consumer': {
        target: 'http://localhost:8085',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/actuator\/behavior-consumer/, '/actuator'),
      },
      '/actuator/recommendation': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/actuator\/recommendation/, '/actuator'),
      },
      '/actuator/notification': {
        target: 'http://localhost:8083',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/actuator\/notification/, '/actuator'),
      },
    },
  },
})

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// В dev-режиме /api проксируется на Spring Boot (8080), чтобы фронтенд
// мог использовать относительные пути и не упираться в CORS.
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_BACKEND_URL || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})

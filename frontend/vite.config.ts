/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// EIP frontend Vite config (P0-E5-S1). In dev, /api and /v3 are proxied to the eip-app so the SPA
// calls the real /api/v1 surface without CORS. Override the proxy target with VITE_API_PROXY_TARGET
// and the dev-server port with VITE_PORT (or FRONTEND_PORT) — `make demo-up` sets these.
const apiTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080';
const devPort = Number(process.env.VITE_PORT ?? process.env.FRONTEND_PORT ?? 5173);

export default defineConfig({
  plugins: [react()],
  server: {
    port: devPort,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
      '/v3': { target: apiTarget, changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});

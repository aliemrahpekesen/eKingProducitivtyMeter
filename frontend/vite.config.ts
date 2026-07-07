import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// EIP frontend Vite config — scaffold. The dev server proxy to eip-app, design tokens, and the
// generated-OpenAPI-client pipeline are added when the app shell lands (FrontendPlan.md §12, Phase 0).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
});

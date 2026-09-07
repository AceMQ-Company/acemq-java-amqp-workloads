/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The build lands in dist/ and Maven copies it into the jar, so `java -jar` is
// the whole installation. In development `npm run dev` proxies the API to a
// studio started from the IDE, which keeps hot reload without a second copy of
// the back end.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 900,
  },
  // Component tests run in jsdom, in the Maven build, on every push. They cover
  // the parts of the interface that are decisions rather than layout: which way
  // a comparison moved, whether an objective survives being typed, what happens
  // to a binding when it is removed. Anything that needs a real browser and a
  // real broker is an end-to-end test instead, under e2e/.
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },

  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8480',
      '/actuator': 'http://localhost:8480',
    },
  },
})

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
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8480',
      '/actuator': 'http://localhost:8480',
    },
  },
})

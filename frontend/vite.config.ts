import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

const backend = process.env.BACKEND_URL ?? "http://localhost:8080";

// changeOrigin must stay false: the backend builds absolute URLs from the Host header, most importantly the Google
// redirect URI. Vite's string shorthand ("/api": backend) turns changeOrigin on, which made Google send people back to
// :8080, where the dev frontend isn't served.
const toBackend = { target: backend, changeOrigin: false };

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": toBackend,
      "/oauth2": toBackend,
      "/login/oauth2": toBackend,
      "/ws": { target: backend.replace(/^http/, "ws"), ws: true, changeOrigin: false },
    },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: false,
  },
  test: {
    environment: "jsdom",
    // One jsdom per worker instead of one per test file: 3x faster, and each file still gets fresh modules.
    pool: "vmThreads",
    globals: false,
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
    restoreMocks: true,
  },
});

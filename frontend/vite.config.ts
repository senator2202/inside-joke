import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import type { Plugin } from "vite";

// 127.0.0.1, not "localhost": Node resolves localhost to the IPv6 address ::1 first on Windows, while the browser and
// PowerShell reach the backend over IPv4. When another process answers on [::1]:8080, the dev server proxied the API
// there and got 404 (the login page then hid the Google button). BACKEND_URL overrides the address.
const backend = process.env.BACKEND_URL ?? "http://127.0.0.1:8080";

/** On `npm run dev`: says which backend the API is proxied to, or warns loudly when it isn't Inside Joke. */
function backendCheck(): Plugin {
  return {
    name: "inside-joke-backend-check",
    apply: "serve",
    configureServer(server) {
      server.httpServer?.once("listening", () => {
        const log = server.config.logger;
        fetch(`${backend}/api/status`, { signal: AbortSignal.timeout(3000) })
          .then(async (res) => {
            const body = (res.ok ? await res.json().catch(() => null) : null) as { version?: unknown } | null;
            if (body && typeof body.version === "string") {
              log.info(`  ➜  Backend: Inside Joke ${body.version} at ${backend}`);
            } else {
              log.warn(
                `  ⚠  ${backend}/api/status answered ${res.status}: that is not the Inside Joke backend, so API calls ` +
                  "will fail. Check what listens on that port, or set BACKEND_URL.",
              );
            }
          })
          .catch(() => log.warn(`  ⚠  No backend at ${backend} yet: API calls fail until it starts (or set BACKEND_URL).`));
      });
    },
  };
}

// changeOrigin must stay false: the backend builds absolute URLs from the Host header, most importantly the Google
// redirect URI. Vite's string shorthand ("/api": backend) turns changeOrigin on, which made Google send people back to
// :8080, where the dev frontend isn't served.
const toBackend = { target: backend, changeOrigin: false };

export default defineConfig({
  plugins: [react(), backendCheck()],
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

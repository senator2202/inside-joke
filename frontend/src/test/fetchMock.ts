import { vi } from "vitest";

export interface MockCall {
  method: string;
  path: string;
  body: unknown;
  headers: Record<string, string>;
}

type Reply = { status: number; body?: unknown } | ((call: MockCall) => { status: number; body?: unknown });

/**
 * Replaces global fetch with a router keyed by "METHOD /path". Handlers registered as arrays reply in turn,
 * the last one repeating. Unregistered routes answer 404 so tests fail loudly instead of hanging.
 */
export function mockFetch(routes: Record<string, Reply | Reply[]>) {
  const calls: MockCall[] = [];
  const counters = new Map<string, number>();
  const fn = vi.fn((input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, "http://localhost");
    const method = (init?.method ?? "GET").toUpperCase();
    const key = `${method} ${url.pathname}`;
    const headers = Object.fromEntries(Object.entries((init?.headers as Record<string, string> | undefined) ?? {}));
    const call: MockCall = {
      method,
      path: url.pathname + url.search,
      body: init?.body ? JSON.parse(init.body as string) : undefined,
      headers,
    };
    calls.push(call);
    if (key === "GET /api/auth/csrf" && !(key in routes)) {
      document.cookie = "XSRF-TOKEN=test-csrf; path=/";
      return Promise.resolve(json(200, { headerName: "X-XSRF-TOKEN", token: "test-csrf" }));
    }
    const entry = routes[key];
    if (entry === undefined) return Promise.resolve(json(404, { error: { code: "NOT_FOUND", message: `No mock for ${key}` } }));
    const list = Array.isArray(entry) ? entry : [entry];
    const n = counters.get(key) ?? 0;
    counters.set(key, n + 1);
    const reply = list[Math.min(n, list.length - 1)]!;
    const { status, body } = typeof reply === "function" ? reply(call) : reply;
    return Promise.resolve(json(status, body));
  });
  vi.stubGlobal("fetch", fn);
  return { calls, fn, callsTo: (key: string) => calls.filter((c) => `${c.method} ${c.path.split("?")[0]}` === key) };
}

export function json(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function apiError(status: number, code: string, details: Record<string, unknown> = {}) {
  return { status, body: { error: { code, message: code, details } } };
}

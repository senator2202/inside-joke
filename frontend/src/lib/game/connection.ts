import { GameError, type RoomView } from "./types";

/**
 * One game WebSocket (blueprint 9) that survives flaky Wi-Fi: it reconnects with backoff, re-sends hello with the
 * room token, queues requests made while offline (a typed answer is sent once the phone is back), and retries once a
 * second when the server says RATE_LIMITED. After 20 seconds without a connection it reports "offline".
 */
export type ConnectionStatus =
  | "connecting" // first attempt, nothing received yet
  | "live"
  | "reconnecting" // lost, retrying, under 20 s
  | "offline" // lost for 20 s or more; still retrying in the background
  | "unreachable" // never managed to connect (X2)
  | "gone" // the room does not exist (X1 / P10 variant)
  | "kicked" // removed by the owner (P11)
  | "closed"; // the owner ended the party (S9 / P10)

export interface SocketLike {
  readyState: number;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onopen: ((ev: Event) => void) | null;
  onmessage: ((ev: MessageEvent) => void) | null;
  onclose: ((ev: CloseEvent) => void) | null;
  onerror: ((ev: Event) => void) | null;
}

export type SocketFactory = (url: string) => SocketLike;

interface Pending {
  type: string;
  data: unknown;
  resolve: (data: Record<string, unknown>) => void;
  reject: (e: GameError) => void;
  attempts: number;
  sent: boolean;
}

export interface ConnectionOptions {
  url?: string;
  socketFactory?: SocketFactory;
  onState: (state: RoomView, clockOffsetMs: number) => void;
  onStatus: (status: ConnectionStatus) => void;
  now?: () => number;
}

const OPEN = 1;
const OFFLINE_AFTER_MS = 20_000;
const UNREACHABLE_AFTER_ATTEMPTS = 6;
const PING_EVERY_MS = 25_000;
const BACKOFF_MS = [500, 1000, 2000, 3000, 5000];
const FINAL = new Set<ConnectionStatus>(["gone", "kicked", "closed"]);

export function defaultSocketUrl(): string {
  const { protocol, host } = window.location;
  return `${protocol === "https:" ? "wss" : "ws"}://${host}/ws`;
}

export class GameConnection {
  private readonly token: string;
  private readonly opts: ConnectionOptions;
  private socket: SocketLike | null = null;
  private status: ConnectionStatus = "connecting";
  private ready = false;
  private everConnected = false;
  private attempts = 0;
  private lostAt: number | null = null;
  private seq = 0;
  private readonly pending = new Map<string, Pending>();
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private stopped = false;

  constructor(token: string, opts: ConnectionOptions) {
    this.token = token;
    this.opts = opts;
  }

  start(): void {
    this.open();
  }

  stop(): void {
    this.stopped = true;
    this.timers.forEach(clearTimeout);
    this.timers.clear();
    if (this.pingTimer) clearInterval(this.pingTimer);
    const s = this.socket;
    this.socket = null;
    if (s) {
      s.onclose = null;
      s.close(1000, "bye");
    }
  }

  /** Reconnects now instead of waiting for the next backoff step ("Retry" buttons). */
  retry(): void {
    if (this.stopped || FINAL.has(this.status) || this.ready) return;
    this.timers.forEach(clearTimeout);
    this.timers.clear();
    this.attempts = 0;
    this.open();
  }

  /** Sends a request; resolves with the server's ok data or rejects with its error. Queued while offline. */
  request(type: string, data: unknown = {}): Promise<Record<string, unknown>> {
    if (FINAL.has(this.status)) return Promise.reject(new GameError("ROOM_NOT_FOUND", "This party is over."));
    return new Promise((resolve, reject) => {
      const reqId = `c-${++this.seq}`;
      this.pending.set(reqId, { type, data, resolve, reject, attempts: 0, sent: false });
      if (this.ready) this.transmit(reqId);
    });
  }

  private now(): number {
    return (this.opts.now ?? Date.now)();
  }

  private setStatus(next: ConnectionStatus): void {
    if (this.status === next || (FINAL.has(this.status) && !FINAL.has(next))) return;
    this.status = next;
    this.opts.onStatus(next);
  }

  private later(fn: () => void, ms: number): void {
    const t = setTimeout(() => {
      this.timers.delete(t);
      fn();
    }, ms);
    this.timers.add(t);
  }

  private open(): void {
    if (this.stopped) return;
    const factory: SocketFactory = this.opts.socketFactory ?? ((url) => new WebSocket(url));
    let socket: SocketLike;
    try {
      socket = factory(this.opts.url ?? defaultSocketUrl());
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;
    this.attempts++;
    socket.onopen = () => {
      socket.send(JSON.stringify({ v: 1, type: "hello", reqId: "hello", data: { token: this.token } }));
    };
    socket.onmessage = (ev) => this.receive(String(ev.data));
    socket.onerror = () => undefined;
    socket.onclose = (ev) => this.lost(socket, ev.code);
  }

  private receive(raw: string): void {
    let msg: { type?: string; reqId?: string; data?: unknown };
    try {
      msg = JSON.parse(raw) as typeof msg;
    } catch {
      return;
    }
    switch (msg.type) {
      case "state": {
        const state = msg.data as RoomView;
        this.opts.onState(state, state.serverTime - this.now());
        return;
      }
      case "ok":
        if (msg.reqId === "hello") {
          this.connected();
          return;
        }
        this.settle(msg.reqId, (p) => p.resolve((msg.data ?? {}) as Record<string, unknown>));
        return;
      case "error": {
        const data = (msg.data ?? {}) as { code?: string; message?: string; details?: Record<string, unknown> };
        const code = data.code ?? "ERROR";
        if (msg.reqId === "hello") {
          this.setStatus("gone");
          this.failAll(new GameError(code, data.message ?? ""));
          return;
        }
        const pending = msg.reqId ? this.pending.get(msg.reqId) : undefined;
        if (pending && code === "RATE_LIMITED" && pending.attempts < 3) {
          pending.sent = false;
          const id = msg.reqId!;
          this.later(() => this.ready && this.transmit(id), 1100);
          return;
        }
        this.settle(msg.reqId, (p) => p.reject(new GameError(code, data.message ?? code, data.details ?? {})));
        return;
      }
      case "kicked":
        this.setStatus("kicked");
        this.failAll(new GameError("KICKED", "Removed from the room."));
        return;
      case "closed":
        this.setStatus("closed");
        this.failAll(new GameError("CLOSED", "The party is over."));
        return;
      default:
        return;
    }
  }

  private connected(): void {
    this.ready = true;
    this.everConnected = true;
    this.attempts = 0;
    this.lostAt = null;
    this.setStatus("live");
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.pingTimer = setInterval(() => this.rawSend({ v: 1, type: "ping", data: {} }), PING_EVERY_MS);
    for (const [id, p] of this.pending) {
      if (!p.sent) this.transmit(id);
    }
  }

  private transmit(reqId: string): void {
    const p = this.pending.get(reqId);
    if (!p) return;
    p.attempts++;
    p.sent = this.rawSend({ v: 1, type: p.type, reqId, data: p.data });
  }

  private rawSend(message: unknown): boolean {
    if (!this.socket || this.socket.readyState !== OPEN) return false;
    this.socket.send(JSON.stringify(message));
    return true;
  }

  private settle(reqId: string | undefined, fn: (p: Pending) => void): void {
    if (!reqId) return;
    const p = this.pending.get(reqId);
    if (!p) return;
    this.pending.delete(reqId);
    fn(p);
  }

  private failAll(error: GameError): void {
    for (const p of this.pending.values()) p.reject(error);
    this.pending.clear();
  }

  private lost(socket: SocketLike, code: number): void {
    if (socket !== this.socket || this.stopped) return;
    this.ready = false;
    this.socket = null;
    if (this.pingTimer) clearInterval(this.pingTimer);
    // Requests in flight when the socket dropped are re-sent after reconnecting.
    for (const p of this.pending.values()) p.sent = false;
    if (code === 4404) {
      this.setStatus("gone");
      this.failAll(new GameError("ROOM_NOT_FOUND", "This party no longer exists."));
      return;
    }
    if (code === 4403) {
      this.setStatus("kicked");
      return;
    }
    if (code === 4410) {
      this.setStatus("closed");
      return;
    }
    if (FINAL.has(this.status)) return;
    this.lostAt ??= this.now();
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (!this.everConnected && this.attempts >= UNREACHABLE_AFTER_ATTEMPTS) {
      this.setStatus("unreachable");
    } else if (this.everConnected) {
      this.setStatus(this.lostAt !== null && this.now() - this.lostAt >= OFFLINE_AFTER_MS ? "offline" : "reconnecting");
      if (this.lostAt !== null) {
        const until = OFFLINE_AFTER_MS - (this.now() - this.lostAt);
        if (until > 0) this.later(() => !this.ready && !FINAL.has(this.status) && this.setStatus("offline"), until);
      }
    }
    const delay = BACKOFF_MS[Math.min(this.attempts, BACKOFF_MS.length - 1)]!;
    this.later(() => this.open(), delay);
  }
}

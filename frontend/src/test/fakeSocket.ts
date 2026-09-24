import type { SocketFactory, SocketLike } from "../lib/game/connection";
import type { RoomView } from "../lib/game/types";

/** In-memory stand-in for the browser WebSocket; tests play the server side. */
export class FakeSocket implements SocketLike {
  readyState = 0;
  sent: { v: number; type: string; reqId?: string; data: Record<string, unknown> }[] = [];
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  closedByClient = false;

  send(data: string): void {
    this.sent.push(JSON.parse(data) as FakeSocket["sent"][number]);
  }

  close(): void {
    this.closedByClient = true;
    this.readyState = 3;
  }

  open(): void {
    this.readyState = 1;
    this.onopen?.(new Event("open"));
  }

  receive(type: string, reqId: string | null, data: unknown = {}): void {
    this.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ v: 1, type, reqId, data }) }));
  }

  /** Accepts the hello and pushes a first snapshot. */
  accept(state?: RoomView): void {
    this.receive("ok", "hello", { role: state?.you.role ?? "PLAYER" });
    if (state) this.receive("state", null, state);
  }

  drop(code = 1006): void {
    this.readyState = 3;
    this.onclose?.(new CloseEvent("close", { code }));
  }

  requests(type: string) {
    return this.sent.filter((m) => m.type === type);
  }
}

export function fakeSockets() {
  const sockets: FakeSocket[] = [];
  const factory: SocketFactory = () => {
    const s = new FakeSocket();
    sockets.push(s);
    return s;
  };
  return { sockets, factory, last: () => sockets[sockets.length - 1]! };
}

export function roomState(overrides: Partial<RoomView> = {}): RoomView {
  return {
    version: 1,
    serverTime: Date.now(),
    code: "KWMP",
    phase: "LOBBY",
    thinking: false,
    locked: false,
    starting: false,
    settings: { tone: "CHEEKY", length: "SHORT", mode: "STANDARD", hideCode: false },
    lobby: { joinUrl: "http://localhost/j/KWMP", audienceCount: 0, minPlayers: 3, maxPlayers: 8 },
    players: [
      { id: "p1", name: "Masha", emoji: "🦊", connected: true, score: 0, captain: true, status: "" },
      { id: "p2", name: "Dima", emoji: "🐙", connected: true, score: 0, captain: false, status: "" },
    ],
    you: { role: "CAPTAIN", playerId: "p1", name: "Masha", emoji: "🦊", score: 0, secretsLeft: 10 },
    ...overrides,
  };
}

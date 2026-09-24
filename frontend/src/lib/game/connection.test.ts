import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeSockets, roomState } from "../../test/fakeSocket";
import { GameConnection, type ConnectionStatus } from "./connection";
import { GameError, type RoomView } from "./types";

function connect() {
  const net = fakeSockets();
  const statuses: ConnectionStatus[] = [];
  const states: RoomView[] = [];
  const conn = new GameConnection("tok-1", {
    url: "ws://test/ws",
    socketFactory: net.factory,
    onState: (s) => states.push(s),
    onStatus: (s) => statuses.push(s),
  });
  conn.start();
  return { net, conn, statuses, states };
}

describe("GameConnection", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("says hello with the room token and goes live", () => {
    const { net, statuses, states } = connect();
    net.last().open();
    expect(net.last().sent[0]).toEqual({ v: 1, type: "hello", reqId: "hello", data: { token: "tok-1" } });
    net.last().accept(roomState());
    expect(statuses).toEqual(["live"]);
    expect(states[0]?.phase).toBe("LOBBY");
  });

  it("resolves requests on ok and rejects with the server's code and details", async () => {
    const { net, conn } = connect();
    net.last().open();
    net.last().accept();
    const good = conn.request("vote.submit", { optionId: "A" });
    const bad = conn.request("intake.submit", { answers: ["a", "b", "c"] });
    const [first, second] = net.last().sent.slice(1);
    net.last().receive("ok", first!.reqId!, { secretsLeft: 9 });
    net.last().receive("error", second!.reqId!, { code: "MODERATION_BLOCKED", message: "no", details: { rejected: [1] } });
    await expect(good).resolves.toEqual({ secretsLeft: 9 });
    const error = await bad.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(GameError);
    expect((error as GameError).code).toBe("MODERATION_BLOCKED");
    expect((error as GameError).details).toEqual({ rejected: [1] });
  });

  it("queues requests made before the connection is up", () => {
    const { net, conn } = connect();
    void conn.request("answer.submit", { duelId: "d1", text: "typed offline" });
    net.last().open();
    expect(net.last().requests("answer.submit")).toHaveLength(0);
    net.last().accept();
    expect(net.last().requests("answer.submit")[0]?.data).toEqual({ duelId: "d1", text: "typed offline" });
  });

  it("waits a second and retries when rate limited", async () => {
    const { net, conn } = connect();
    net.last().open();
    net.last().accept();
    const reply = conn.request("vote.submit", { optionId: "B" });
    const first = net.last().requests("vote.submit")[0]!;
    net.last().receive("error", first.reqId!, { code: "RATE_LIMITED", message: "slow down" });
    expect(net.last().requests("vote.submit")).toHaveLength(1);
    vi.advanceTimersByTime(1100);
    expect(net.last().requests("vote.submit")).toHaveLength(2);
    net.last().receive("ok", first.reqId!, {});
    await expect(reply).resolves.toEqual({});
  });

  it("reconnects after a drop, re-sends what was in flight, and reports offline after 20 seconds", () => {
    const { net, conn, statuses } = connect();
    net.last().open();
    net.last().accept();
    void conn.request("answer.submit", { duelId: "d1", text: "hello" });
    net.last().drop();
    expect(statuses.at(-1)).toBe("reconnecting");

    vi.advanceTimersByTime(600);
    expect(net.sockets).toHaveLength(2);
    net.last().drop();
    vi.advanceTimersByTime(20_000);
    expect(statuses.at(-1)).toBe("offline");

    const socket = net.sockets.at(-1)!;
    socket.open();
    expect(socket.sent[0]?.type).toBe("hello");
    socket.accept();
    expect(statuses.at(-1)).toBe("live");
    expect(socket.requests("answer.submit")[0]?.data).toEqual({ duelId: "d1", text: "hello" });
  });

  it("treats a 'too slow' disconnect like any drop: reconnect and take a fresh snapshot", () => {
    const { net, statuses, states } = connect();
    net.last().open();
    net.last().accept(roomState({ version: 5 }));
    net.last().drop(4408);
    expect(statuses.at(-1)).toBe("reconnecting");
    vi.advanceTimersByTime(600);
    net.last().open();
    net.last().accept(roomState({ version: 9 }));
    expect(statuses.at(-1)).toBe("live");
    expect(states.at(-1)?.version).toBe(9);
  });

  it("gives up with 'unreachable' when the socket never comes up", () => {
    const { net, statuses } = connect();
    for (let i = 0; i < 6; i++) {
      net.last().drop();
      vi.advanceTimersByTime(5000);
    }
    expect(statuses).toContain("unreachable");
  });

  it("knows when the room is gone, when it was kicked and when the party ended", async () => {
    const a = connect();
    a.net.last().open();
    a.net.last().receive("error", "hello", { code: "ROOM_NOT_FOUND", message: "gone" });
    expect(a.statuses).toEqual(["gone"]);
    await expect(a.conn.request("vote.submit")).rejects.toBeInstanceOf(GameError);

    const b = connect();
    b.net.last().open();
    b.net.last().accept();
    const pending = b.conn.request("vote.submit", { optionId: "A" });
    b.net.last().receive("kicked", null);
    b.net.last().drop(4403);
    expect(b.statuses.at(-1)).toBe("kicked");
    await expect(pending).rejects.toMatchObject({ code: "KICKED" });
    vi.advanceTimersByTime(10_000);
    expect(b.net.sockets).toHaveLength(1);

    const c = connect();
    c.net.last().open();
    c.net.last().accept();
    c.net.last().receive("closed", null);
    expect(c.statuses.at(-1)).toBe("closed");
  });

  it("stop() closes the socket and never reconnects", () => {
    const { net, conn } = connect();
    net.last().open();
    net.last().accept();
    conn.stop();
    expect(net.last().closedByClient).toBe(true);
    vi.advanceTimersByTime(30_000);
    expect(net.sockets).toHaveLength(1);
  });
});

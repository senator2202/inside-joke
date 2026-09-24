import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { loadSeat, saveSeat } from "../../lib/game/storage";
import { setSocketFactoryForTests } from "../../lib/game/useGame";
import { setPaddleLoaderForTests } from "../../lib/paddle";
import { apiError, mockFetch } from "../../test/fetchMock";
import { fakeSockets, roomState } from "../../test/fakeSocket";
import { renderRoute } from "../../test/render";
import { ScreenPage } from "./ScreenPage";

describe("ScreenPage", () => {
  let net: ReturnType<typeof fakeSockets>;
  beforeEach(() => {
    net = fakeSockets();
    setSocketFactoryForTests(net.factory);
  });
  afterEach(() => setSocketFactoryForTests(undefined));

  it("opens the owner's lobby and drives the room from the menu", async () => {
    mockFetch({ "GET /api/me": apiError(401, "UNAUTHORIZED") });
    saveSeat("KWMP", { token: "owner-tok", kind: "owner" });
    renderRoute("/screen/KWMP", "/screen/:code", <ScreenPage />);
    const socket = net.last();
    act(() => {
      socket.open();
      socket.accept(roomState({ you: { role: "OWNER_SCREEN" } }));
    });
    expect(await screen.findByRole("heading", { name: "Need 1 more player" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Menu" }));
    fireEvent.click(screen.getByRole("button", { name: "Close entry" }));
    expect(socket.requests("room.lock")[0]?.data).toEqual({ locked: true });

    act(() =>
      socket.receive(
        "state",
        null,
        roomState({
          version: 3,
          you: { role: "OWNER_SCREEN" },
          phase: "ANSWERING",
          paused: { reason: "OWNER", welcomeBack: false },
          round: { n: 1, of: 5, kind: "ANSWER_DUEL", progress: [] },
        }),
      ),
    );
    expect(await screen.findByRole("dialog", { name: "Paused" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(socket.requests("game.resume")).toHaveLength(1);
  });

  it("recovers the owner's token from the server after a reload elsewhere", async () => {
    mockFetch({
      "GET /api/me": { status: 200, body: { id: "u1", email: "a@b.co", displayName: null, role: "HOST", googleLinked: false } },
      "GET /api/rooms/KWMP/owner-token": { status: 200, body: { screenToken: "owner-tok-2" } },
    });
    renderRoute("/screen/KWMP", "/screen/:code", <ScreenPage />);
    await waitFor(() => expect(net.sockets).toHaveLength(1));
    act(() => net.last().open());
    expect(net.last().sent[0]?.data).toEqual({ token: "owner-tok-2" });
    expect(loadSeat("KWMP", "owner")?.token).toBe("owner-tok-2");
  });

  it("shows X1 when the room belongs to someone else or is gone", async () => {
    mockFetch({
      "GET /api/me": { status: 200, body: { id: "u2", email: "c@d.co", displayName: null, role: "HOST", googleLinked: false } },
      "GET /api/rooms/KWMP/owner-token": apiError(403, "FORBIDDEN"),
    });
    renderRoute("/screen/KWMP", "/screen/:code", <ScreenPage />);
    expect(await screen.findByRole("heading", { name: "This party has ended, or the code is wrong" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create a new one" })).toHaveAttribute("href", "/new");
  });

  it("opens a view-only copy for remote players with sound off until asked", async () => {
    const mock = mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "POST /api/rooms/KWMP/screens": { status: 201, body: { screenToken: "copy-tok" } },
    });
    renderRoute("/view/KWMP", "/view/:code", <ScreenPage remote />);
    await waitFor(() => expect(net.sockets).toHaveLength(1));
    act(() => {
      net.last().open();
      net.last().accept(roomState({ you: { role: "SCREEN" } }));
    });
    expect(await screen.findByRole("button", { name: "🔊 Turn on sound" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Menu" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
    expect(mock.callsTo("POST /api/rooms/KWMP/screens")).toHaveLength(1);
  });

  it("thanks the owner and asks for a rating when the party closes", async () => {
    const mock = mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "POST /api/games/s-1/feedback": { status: 204 },
    });
    saveSeat("KWMP", { token: "owner-tok", kind: "owner" });
    renderRoute("/screen/KWMP", "/screen/:code", <ScreenPage />);
    const socket = net.last();
    act(() => {
      socket.open();
      socket.accept(roomState({ you: { role: "OWNER_SCREEN" } }));
      socket.receive("state", null, roomState({ version: 9, phase: "CLOSED", sessionId: "s-1", you: { role: "OWNER_SCREEN" } }));
      socket.receive("closed", null);
    });
    expect(await screen.findByRole("heading", { name: "Thanks for the party!" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("radio", { name: "4 stars" }));
    fireEvent.click(screen.getByRole("button", { name: "Rate" }));
    expect(await screen.findByText("Thanks for the feedback!")).toBeInTheDocument();
    expect(mock.callsTo("POST /api/games/s-1/feedback")[0]!.body).toEqual({ rating: 4 });
  });

  it("opens the pass picker on a paywall and hands 'Not now' back to the room", async () => {
    mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "GET /api/billing/checkout": {
        status: 200,
        body: {
          available: true,
          environment: "sandbox",
          clientToken: "t",
          offers: [{ product: "PARTY_PASS", priceId: "pri_party", listPriceUsdCents: 299, monthlyGameLimit: null, validityHours: 24 }],
          email: "a@b.co",
          customData: { userId: "u1" },
          supportEmail: "s@x.co",
        },
      },
      "GET /api/billing/passes": {
        status: 200,
        body: {
          access: {
            freeGamesEnabled: true,
            freeGameAvailable: false,
            nextFreeGameAt: null,
            passes: [],
            nextGame: "PAYWALL",
            paywallReason: "PAYWALL_FREE_LIMIT",
          },
          drainMode: false,
        },
      },
    });
    setPaddleLoaderForTests(() =>
      Promise.resolve({
        openCheckout: () => undefined,
        closeCheckout: () => undefined,
        previewPrices: () => Promise.resolve({}),
        onEvent: () => () => undefined,
      }),
    );
    saveSeat("KWMP", { token: "owner-tok", kind: "owner" });
    renderRoute("/screen/KWMP", "/screen/:code", <ScreenPage />);
    const socket = net.last();
    act(() => {
      socket.open();
      socket.accept(roomState({ you: { role: "OWNER_SCREEN" }, paywall: "PAYWALL_FREE_LIMIT" }));
    });
    expect(await screen.findByRole("dialog", { name: "This week's free game is already played" })).toBeInTheDocument();
    expect(await screen.findByText("$2.99")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Not now" }));
    expect(socket.requests("paywall.dismiss")).toHaveLength(1);
    setPaddleLoaderForTests(null);
  });
});
